package ai.pdlc.controlplane.identity;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * A non-human caller authenticated by a static dev/shared token ({@code X-Agent-Token},
 * {@code X-Service-Token}, {@code X-Webhook-Token}). Carries only the one scope that token grants,
 * never {@link PdlcAuthorities#USER}. Production services use client-credentials JWTs instead,
 * which arrive as Spring's {@code JwtAuthenticationToken} with the same {@code SCOPE_*} names.
 */
public final class ServiceAuthentication extends AbstractAuthenticationToken {

    private final String service;

    public ServiceAuthentication(String service, String authority) {
        super(AuthorityUtils.createAuthorityList(authority));
        this.service = service;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return service;
    }
}
