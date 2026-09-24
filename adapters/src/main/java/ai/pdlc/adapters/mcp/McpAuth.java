package ai.pdlc.adapters.mcp;

/**
 * How an {@link McpHttpClient} authenticates. {@link #header()} is sent on every request (null =
 * none); on a 401 the client calls {@link #onUnauthorized} once and retries only if it returns true.
 */
public interface McpAuth {

    String header();

    default boolean onUnauthorized(String wwwAuthenticate) {
        return false;
    }

    McpAuth NONE = () -> null;

    /** A static bearer token (the connection's {@code kv://} reference, resolved by the caller). */
    static McpAuth bearer(String token) {
        return () -> "Bearer " + token;
    }
}
