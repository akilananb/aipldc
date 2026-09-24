package ai.pdlc.controlplane.web.dto;

/**
 * What the UI needs to decide how to authenticate: {@code mode} is {@code dev-headers} (send
 * {@code X-User}/{@code X-Role}), {@code oidc} (browser session; {@code loginUrl} starts login) or
 * {@code none} (nothing configured). {@code user}/{@code role} are set when authenticated;
 * {@code error} explains an authenticated user with no mapped PDLC role.
 */
public record MeDto(String mode, boolean oidcEnabled, boolean authenticated, String user, String role,
                    String loginUrl, String logoutUrl, String error) {
}
