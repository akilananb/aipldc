package ai.pdlc.controlplane.identity;

import ai.pdlc.controlplane.web.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * The single place a controller turns the current request into a PDLC {@link Identity}
 * ({@code user} + {@code role}). Authentication itself happens in the Spring Security chain
 * ({@link SecurityConfig}); this reads its result:
 * <ul>
 *   <li>enterprise OIDC session → user claim + highest mapped role ({@link OidcIdentityMapper});
 *       a user with no mapped role → 403;</li>
 *   <li>{@code X-User}/{@code X-Role} dev headers → as sent, only when
 *       {@code pdlc.identity.dev-headers=true} ({@link HeaderAuthenticationFilter});</li>
 *   <li>a service credential (client-credentials JWT or shared token) → 403: services never act
 *       as a user;</li>
 *   <li>nothing → 401.</li>
 * </ul>
 */
@Component
public class IdentityResolver {

    private final IdentityProperties properties;
    private final OidcIdentityMapper oidcMapper;

    public IdentityResolver(IdentityProperties properties, OidcIdentityMapper oidcMapper) {
        this.properties = properties;
        this.oidcMapper = oidcMapper;
    }

    public Identity resolve(HttpServletRequest request) {
        return current();
    }

    public Identity current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new UnauthenticatedException(properties.devHeaders()
                    ? "Missing X-User/X-Role headers"
                    : "Authentication required");
        }
        if (auth instanceof UserAuthentication user) {
            return user.identity();
        }
        if (auth instanceof OAuth2AuthenticationToken token && token.getPrincipal() instanceof OidcUser oidc) {
            return oidcMapper.identity(oidc.getClaims())
                    .orElseThrow(() -> new ForbiddenException("No PDLC role is mapped for " + oidcMapper.user(oidc.getClaims())));
        }
        throw new ForbiddenException("Service credentials cannot act as a user");
    }
}
