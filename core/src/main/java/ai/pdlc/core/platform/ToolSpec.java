package ai.pdlc.core.platform;

import java.util.Map;

/**
 * The publishable content of a platform {@code ToolDefinition} (Phase 2 slice 2.1,
 * docs/phase-2-execution-spec.md): one HTTP operation against an {@code HTTP_API} connection.
 * Versioned and content-hashed like {@link AgentSpec}. Descriptions are shown to the model; they are
 * never trusted for authorization.
 *
 * @param description      what the model is told the tool does
 * @param kind             {@code http} (MCP tools arrive in slice 2.3)
 * @param connectionId     the {@code HTTP_API} connection supplying base URL and credentials
 * @param method           GET, POST, PUT, PATCH or DELETE
 * @param path             path template appended to the connection's base URL, e.g. {@code /orders/{orderId}}
 * @param inputSchema      arguments schema (the {@link OutputSchema} subset; top-level {@code type: object})
 * @param effect           {@code READ} or {@code WRITE}; WRITE needs approval (slice 2.2)
 * @param timeoutSeconds   per-call deadline
 * @param maxResponseBytes response bytes returned to the model; longer bodies are truncated and flagged
 */
public record ToolSpec(
        String description,
        String kind,
        String connectionId,
        String method,
        String path,
        Map<String, Object> inputSchema,
        String effect,
        Integer timeoutSeconds,
        Integer maxResponseBytes) {

    public static final String KIND_HTTP = "http";
    public static final String READ = "READ";
    public static final String WRITE = "WRITE";
}
