package ai.pdlc.core.platform;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param idempotency      WRITE tools (slice 2.2): {@code HEADER} = the target honors an
 *                         {@code Idempotency-Key} header, so an unknown outcome can be resent safely;
 *                         {@code NONE} (null) = it cannot, and an unknown outcome waits for an operator
 * @param approval         WRITE tools: when a pending approval escalates and when it expires (null = defaults)
 * @param mcpTool          kind {@code mcp} (slice 2.3): the remote tool's name on the MCP server
 * @param mcpFingerprint   kind {@code mcp}: {@link McpFingerprint} of the server's definition that was
 *                         reviewed; a call is refused when the server's current definition differs
 * @param sandboxImage     kind {@code sandbox} (slice 2.4): the enterprise catalog entry the tool runs
 * @param sandboxImageRef  kind {@code sandbox}: the digest-pinned image reference reviewed with it; a call
 *                         is refused if the catalog entry no longer carries exactly this reference
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
        Integer maxResponseBytes,
        @JsonInclude(JsonInclude.Include.NON_NULL) String idempotency,
        @JsonInclude(JsonInclude.Include.NON_NULL) Approval approval,
        @JsonInclude(JsonInclude.Include.NON_NULL) String mcpTool,
        @JsonInclude(JsonInclude.Include.NON_NULL) String mcpFingerprint,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sandboxImage,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sandboxImageRef) {

    public static final String KIND_HTTP = "http";
    public static final String KIND_MCP = "mcp";
    public static final String KIND_SANDBOX = "sandbox";
    public static final String READ = "READ";
    public static final String WRITE = "WRITE";
    public static final String IDEMPOTENCY_HEADER = "HEADER";
    public static final String IDEMPOTENCY_NONE = "NONE";

    /** Slice 2.1 shape (no idempotency or approval settings); keeps those versions' hashes. */
    public ToolSpec(String description, String kind, String connectionId, String method, String path,
                    Map<String, Object> inputSchema, String effect, Integer timeoutSeconds, Integer maxResponseBytes) {
        this(description, kind, connectionId, method, path, inputSchema, effect, timeoutSeconds, maxResponseBytes, null, null);
    }

    /** Slice 2.2 shape (no MCP fields); keeps those versions' hashes. */
    public ToolSpec(String description, String kind, String connectionId, String method, String path,
                    Map<String, Object> inputSchema, String effect, Integer timeoutSeconds, Integer maxResponseBytes,
                    String idempotency, Approval approval) {
        this(description, kind, connectionId, method, path, inputSchema, effect, timeoutSeconds, maxResponseBytes,
                idempotency, approval, null, null);
    }

    /** Slice 2.3 shape (no sandbox fields); keeps those versions' hashes. */
    public ToolSpec(String description, String kind, String connectionId, String method, String path,
                    Map<String, Object> inputSchema, String effect, Integer timeoutSeconds, Integer maxResponseBytes,
                    String idempotency, Approval approval, String mcpTool, String mcpFingerprint) {
        this(description, kind, connectionId, method, path, inputSchema, effect, timeoutSeconds, maxResponseBytes,
                idempotency, approval, mcpTool, mcpFingerprint, null, null);
    }

    @JsonIgnore
    public boolean isSandbox() {
        return KIND_SANDBOX.equals(kind);
    }

    @JsonIgnore
    public boolean isMcp() {
        return KIND_MCP.equals(kind);
    }

    /** Approval timing for a WRITE call; there is no auto-approval, expiry means denied. */
    public record Approval(Integer escalateAfterMinutes, Integer expireAfterMinutes) {

        public static final int DEFAULT_ESCALATE_AFTER_MINUTES = 60;
        public static final int DEFAULT_EXPIRE_AFTER_MINUTES = 1_440;

        public int escalateAfter() {
            return escalateAfterMinutes == null ? DEFAULT_ESCALATE_AFTER_MINUTES : escalateAfterMinutes;
        }

        public int expireAfter() {
            return expireAfterMinutes == null ? DEFAULT_EXPIRE_AFTER_MINUTES : expireAfterMinutes;
        }
    }

    public Approval approvalOrDefault() {
        return approval == null ? new Approval(null, null) : approval;
    }

    public boolean idempotentByHeader() {
        return IDEMPOTENCY_HEADER.equals(idempotency);
    }
}
