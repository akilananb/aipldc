package ai.pdlc.controlplane.identity;

/** A resolved human caller: {@code user} (OIDC user claim, or {@code X-User} in dev mode) and PDLC {@code role}. See {@link IdentityResolver}. */
public record Identity(String user, String role) {
}
