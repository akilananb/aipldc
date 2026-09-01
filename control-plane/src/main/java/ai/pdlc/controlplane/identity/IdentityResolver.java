package ai.pdlc.controlplane.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Pilot identity = dev headers {@code X-User} (OIDC-sub stand-in) and {@code X-Role}
 * ({@code PO|SquadLead|FSDeveloper|QA}); missing headers → 401. OIDC is a later build-order phase;
 * this is the single dev-only auth shim (plan step 7) — swapping in Spring Security OIDC means
 * changing only this class.
 */
@Component
public class IdentityResolver {

    public Identity resolve(HttpServletRequest request) {
        String user = request.getHeader("X-User");
        String role = request.getHeader("X-Role");
        if (user == null || user.isBlank() || role == null || role.isBlank()) {
            throw new UnauthenticatedException("Missing X-User/X-Role headers");
        }
        return new Identity(user, role);
    }
}
