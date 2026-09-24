package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityProperties;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.identity.SecurityConfig;
import ai.pdlc.controlplane.identity.UnauthenticatedException;
import ai.pdlc.controlplane.web.dto.MeDto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/me} - always reachable. Tells the UI which authentication mode is active and who
 * the caller is. Also renders the SPA CSRF cookie ({@code XSRF-TOKEN}) so the UI has a token before
 * its first mutation.
 */
@RestController
public class MeController {

    private final IdentityResolver identityResolver;
    private final IdentityProperties properties;
    private final boolean oidc;

    public MeController(IdentityResolver identityResolver, IdentityProperties properties,
                        ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.identityResolver = identityResolver;
        this.properties = properties;
        this.oidc = clientRegistrations.getIfAvailable() != null;
    }

    @GetMapping("/api/me")
    public MeDto me(CsrfToken csrfToken) {
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        String mode = properties.devHeaders() ? "dev-headers" : oidc ? "oidc" : "none";
        String loginUrl = oidc ? "/oauth2/authorization/" + SecurityConfig.OIDC_REGISTRATION_ID : null;
        try {
            Identity identity = identityResolver.current();
            return new MeDto(mode, oidc, true, identity.user(), identity.role(), loginUrl, "/logout", null);
        } catch (UnauthenticatedException e) {
            return new MeDto(mode, oidc, false, null, null, loginUrl, null, null);
        } catch (ForbiddenException e) {
            return new MeDto(mode, oidc, true, null, null, loginUrl, "/logout", e.getMessage());
        }
    }
}
