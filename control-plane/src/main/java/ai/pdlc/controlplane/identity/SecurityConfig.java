package ai.pdlc.controlplane.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * Control-plane authentication and coarse authorization (docs/phase-1-execution-spec.md slice 2).
 * Fine-grained checks (gate roles, workspace capabilities) stay in the services, which read the
 * result through {@link IdentityResolver}.
 *
 * <p>Mechanisms, each enabled only by configuration:
 * <ul>
 *   <li><b>Enterprise OIDC login</b> - when {@code spring.security.oauth2.client.registration.pdlc.*}
 *       is configured: server-side session, HttpOnly cookie, SPA CSRF cookie
 *       ({@code XSRF-TOKEN} → {@code X-XSRF-TOKEN}).</li>
 *   <li><b>Service JWTs</b> - when {@code spring.security.oauth2.resourceserver.jwt.*} is configured:
 *       client-credentials bearer tokens whose {@code scope} grants {@code pdlc.build},
 *       {@code pdlc.board.read} or {@code pdlc.webhook}.</li>
 *   <li><b>Shared tokens and dev headers</b> - {@link HeaderAuthenticationFilter}.</li>
 * </ul>
 *
 * <p>Rules: {@code /api/**} needs a human user ({@link PdlcAuthorities#USER}); service credentials
 * reach only their own paths. With {@code pdlc.identity.dev-headers=true} (local stack only) the
 * pre-slice-2 behaviour is kept: reads stay open, mutations still need the headers via
 * {@link IdentityResolver}, and build-tasks/board stay open unless their tokens are set.
 * Unauthenticated API calls get JSON 401, never a login redirect.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    public static final String OIDC_REGISTRATION_ID = "pdlc";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   IdentityProperties props,
                                                   OidcIdentityMapper oidcMapper,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                                   ObjectProvider<JwtDecoder> jwtDecoders,
                                                   @Value("${pdlc.build-agent.token:}") String buildAgentToken,
                                                   @Value("${pdlc.active-profile:local}") String activeProfile) throws Exception {
        boolean oidc = clientRegistrations.getIfAvailable() != null;
        boolean jwt = jwtDecoders.getIfAvailable() != null;
        boolean dev = props.devHeaders();
        warnAboutConfiguration(props, oidc, jwt, dev, activeProfile);

        http.cors(c -> c.configurationSource(corsConfigurationSource));
        if (dev && !oidc) {
            // No cookie-based login exists, so there is nothing for CSRF to protect - and the
            // local e2e scripts POST without any headers (e.g. /api/demo/live/start).
            http.csrf(c -> c.disable());
        } else {
            http.csrf(c -> c.spa().ignoringRequestMatchers(csrfExempt(dev, jwt)));
        }
        http.addFilterBefore(new HeaderAuthenticationFilter(dev, buildAgentToken, props.serviceToken(), props.webhookToken()),
                AnonymousAuthenticationFilter.class);
        http.exceptionHandling(e -> e.authenticationEntryPoint(json401()).accessDeniedHandler(json403()));
        http.logout(l -> l.logoutUrl("/logout").logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        http.authorizeHttpRequests(a -> {
            a.requestMatchers("/api/me", "/error").permitAll();
            openOr(a, "/api/build-tasks/**", dev && buildAgentToken.isBlank(), PdlcAuthorities.SCOPE_BUILD);
            openOr(a, "/api/board/**", dev && props.serviceToken().isBlank(), PdlcAuthorities.SCOPE_BOARD_READ, PdlcAuthorities.USER);
            openOr(a, "/webhooks/**", props.webhookToken().isBlank(), PdlcAuthorities.SCOPE_WEBHOOK);
            if (dev) {
                a.requestMatchers("/api/**").permitAll();
            } else {
                a.requestMatchers("/api/metrics/**", "/api/metrics").authenticated();
                a.requestMatchers("/api/**").hasAuthority(PdlcAuthorities.USER);
            }
            a.anyRequest().permitAll();
        });

        if (oidc) {
            SimpleUrlAuthenticationSuccessHandler success = new SimpleUrlAuthenticationSuccessHandler(props.postLoginRedirect());
            success.setAlwaysUseDefaultTargetUrl(true);
            http.oauth2Login(o -> o
                    .userInfoEndpoint(u -> u.userAuthoritiesMapper(oidcMapper))
                    .successHandler(success));
        }
        if (jwt) {
            http.oauth2ResourceServer(r -> r.jwt(Customizer.withDefaults()).authenticationEntryPoint(json401()));
        }
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(IdentityProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(props.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private static void openOr(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry a,
                               String path, boolean open, String... authorities) {
        if (open) {
            a.requestMatchers(path).permitAll();
        } else {
            a.requestMatchers(path).hasAnyAuthority(authorities);
        }
    }

    /**
     * CSRF protects cookie-authenticated browser requests only. Requests that authenticate with a
     * header (bearer JWT, shared token, dev headers) can't be forged cross-site - a browser can't
     * attach those headers to another origin's request without passing CORS.
     */
    private static RequestMatcher csrfExempt(boolean dev, boolean jwt) {
        return request -> request.getRequestURI().startsWith("/webhooks/")
                || request.getRequestURI().startsWith("/api/build-tasks/")
                || request.getHeader("X-Agent-Token") != null
                || request.getHeader("X-Service-Token") != null
                || request.getHeader("X-Webhook-Token") != null
                || (jwt && bearer(request))
                || (dev && request.getHeader("X-User") != null);
    }

    private static boolean bearer(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private static AuthenticationEntryPoint json401() {
        return (request, response, e) -> writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /** An anonymous caller is told to authenticate (401) even when the first check to fail was
     * CSRF; an authenticated one gets 403, naming a CSRF failure so the UI can reload its token. */
    private static AccessDeniedHandler json403() {
        return (request, response, e) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth instanceof AnonymousAuthenticationToken) {
                writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required");
            } else if (e instanceof CsrfException) {
                writeError(response, HttpStatus.FORBIDDEN, "Invalid or missing CSRF token");
            } else {
                writeError(response, HttpStatus.FORBIDDEN, "Access denied");
            }
        };
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private static void warnAboutConfiguration(IdentityProperties props, boolean oidc, boolean jwt, boolean dev, String activeProfile) {
        if (dev && !"local".equals(activeProfile)) {
            log.warn("pdlc.identity.dev-headers=true on profile '{}': anyone can claim any identity with X-User/X-Role. "
                    + "Use only on a local development stack.", activeProfile);
        }
        if (!dev && !oidc) {
            log.warn("No OIDC client registration '{}' configured and dev headers are off: every user endpoint will answer 401.",
                    OIDC_REGISTRATION_ID);
        }
        if (!dev && props.webhookToken().isBlank()) {
            log.warn("pdlc.identity.webhook-token is not set: /webhooks/** accepts unauthenticated board events.");
        }
        if (!dev && !props.serviceToken().isBlank()) {
            log.warn("pdlc.identity.service-token is set outside dev mode; prefer client-credentials JWTs for services.");
        }
    }
}
