package ai.pdlc.controlplane.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * {@code pdlc.identity.*} (docs/phase-1-execution-spec.md slice 2). OIDC login and service JWTs
 * are configured with Spring's standard {@code spring.security.oauth2.client.registration.pdlc.*}
 * and {@code spring.security.oauth2.resourceserver.jwt.*} keys; these properties map the enterprise
 * IdP's claims onto a PDLC {@link Identity} and control the dev-only escape hatches.
 *
 * @param devHeaders        honour the {@code X-User}/{@code X-Role} header shim; off unless explicitly enabled
 * @param userClaim         OIDC claim used as {@link Identity#user()}; falls back to {@code sub} when absent
 * @param roleClaim         OIDC claim holding the user's IdP groups/roles (string or list)
 * @param roleMapping       IdP group → PDLC role ({@code PO|SquadLead|FSDeveloper|QA|Admin})
 * @param postLoginRedirect where the browser lands after a successful login (the UI)
 * @param allowedOrigins    CORS origin patterns allowed to call the API with credentials
 * @param serviceToken      dev-only static token for service callers ({@code X-Service-Token});
 *                          blank disables it - production services use client-credentials JWTs
 * @param webhookToken      shared secret required in {@code X-Webhook-Token} on {@code /webhooks/**}
 */
@ConfigurationProperties("pdlc.identity")
public record IdentityProperties(
        boolean devHeaders,
        String userClaim,
        String roleClaim,
        Map<String, String> roleMapping,
        String postLoginRedirect,
        List<String> allowedOrigins,
        String serviceToken,
        String webhookToken) {

    public IdentityProperties {
        userClaim = blank(userClaim) ? "email" : userClaim;
        roleClaim = blank(roleClaim) ? "groups" : roleClaim;
        roleMapping = roleMapping == null ? Map.of() : Map.copyOf(roleMapping);
        postLoginRedirect = blank(postLoginRedirect) ? "http://localhost:5173/" : postLoginRedirect;
        allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty() ? List.of("http://localhost:*") : List.copyOf(allowedOrigins);
        serviceToken = serviceToken == null ? "" : serviceToken;
        webhookToken = webhookToken == null ? "" : webhookToken;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
