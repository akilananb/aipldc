package ai.pdlc.adapters.mcp;

/**
 * A failed MCP exchange. Messages describe what went wrong (status, JSON-RPC error, policy) and
 * never carry credentials or response bodies beyond the server's own error message.
 * {@code maybeSent} is true when the request may have reached the server (a write's outcome is then unknown).
 */
public class McpException extends RuntimeException {

    private final boolean maybeSent;

    public McpException(String message, boolean maybeSent) {
        super(message);
        this.maybeSent = maybeSent;
    }

    public boolean maybeSent() {
        return maybeSent;
    }
}
