package ai.pdlc.controlplane.identity;

/** Resolved caller identity from the dev-mode header shim (X-User/X-Role). */
public record Identity(String user, String role) {
}
