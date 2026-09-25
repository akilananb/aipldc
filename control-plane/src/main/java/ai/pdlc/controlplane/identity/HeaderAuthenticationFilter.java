package ai.pdlc.controlplane.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Turns the header-based credentials this platform still accepts into a Spring
 * {@link Authentication}, so every mechanism goes through the same authorization rules:
 * <ul>
 *   <li>{@code X-User}/{@code X-Role} → {@link UserAuthentication}, only when
 *       {@code pdlc.identity.dev-headers=true};</li>
 *   <li>{@code X-Agent-Token} → build-worker service ({@code SCOPE_pdlc.build}) when
 *       {@code pdlc.build-agent.token} is set;</li>
 *   <li>{@code X-Service-Token} → dev service ({@code SCOPE_pdlc.board.read}) when
 *       {@code pdlc.identity.service-token} is set;</li>
 *   <li>{@code X-Webhook-Token} → webhook sender ({@code SCOPE_pdlc.webhook}) when
 *       {@code pdlc.identity.webhook-token} is set.</li>
 * </ul>
 * Tokens compare in constant time. A wrong token is simply not an authentication - the
 * authorization rules then answer 401/403. An existing authentication (session or bearer JWT)
 * is never replaced.
 */
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    private final boolean devHeaders;
    private final String buildAgentToken;
    private final String serviceToken;
    private final String webhookToken;

    public HeaderAuthenticationFilter(boolean devHeaders, String buildAgentToken, String serviceToken, String webhookToken) {
        this.devHeaders = devHeaders;
        this.buildAgentToken = buildAgentToken == null ? "" : buildAgentToken;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.webhookToken = webhookToken == null ? "" : webhookToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null || !existing.isAuthenticated()) {
            Authentication resolved = resolve(request);
            if (resolved != null) {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(resolved);
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }

    private Authentication resolve(HttpServletRequest request) {
        if (matches(buildAgentToken, request.getHeader("X-Agent-Token"))) {
            return new ServiceAuthentication("build-worker", PdlcAuthorities.SCOPE_BUILD);
        }
        if (matches(serviceToken, request.getHeader("X-Service-Token"))) {
            return new ServiceAuthentication("service", PdlcAuthorities.SCOPE_BOARD_READ);
        }
        if (matches(webhookToken, request.getHeader("X-Webhook-Token"))) {
            return new ServiceAuthentication("webhook", PdlcAuthorities.SCOPE_WEBHOOK);
        }
        if (devHeaders) {
            String user = request.getHeader("X-User");
            String role = request.getHeader("X-Role");
            if (user != null && !user.isBlank() && role != null && !role.isBlank()) {
                return new UserAuthentication(new Identity(user, role));
            }
        }
        return null;
    }

    static boolean matches(String expected, String presented) {
        if (expected.isEmpty() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
