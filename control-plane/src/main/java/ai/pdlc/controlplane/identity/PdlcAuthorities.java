package ai.pdlc.controlplane.identity;

/**
 * Granted authorities the security chain reasons about. Only human users ever carry
 * {@link #USER}; service callers carry {@code SCOPE_*} authorities only, so a service credential
 * can never act on a user endpoint.
 */
public final class PdlcAuthorities {

    public static final String USER = "ROLE_USER";
    /** build-worker: claim/heartbeat/result on {@code /api/build-tasks/**}. */
    public static final String SCOPE_BUILD = "SCOPE_pdlc.build";
    /** agents: read-only board proxy on {@code /api/board/**}. */
    public static final String SCOPE_BOARD_READ = "SCOPE_pdlc.board.read";
    /** board webhooks on {@code /webhooks/**}. */
    public static final String SCOPE_WEBHOOK = "SCOPE_pdlc.webhook";

    private PdlcAuthorities() {
    }
}
