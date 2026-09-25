package ai.pdlc.core.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSpecValidatorTest {

    static ToolSpec valid() {
        return new ToolSpec("Look up an order", "http", "orders-api", "GET", "/orders/{orderId}",
                Map.of("type", "object",
                        "properties", Map.of("orderId", Map.of("type", "string"), "expand", Map.of("type", "boolean")),
                        "required", List.of("orderId")),
                "READ", 10, 65_536);
    }

    @Test
    void acceptsAValidReadTool() {
        assertThat(ToolSpecValidator.validate("get_order", valid())).isEmpty();
    }

    @Test
    void onlyGetIsReadOnly() {
        ToolSpec v = valid();
        ToolSpec postAsRead = new ToolSpec(v.description(), v.kind(), v.connectionId(), "POST", v.path(), v.inputSchema(), "READ",
                v.timeoutSeconds(), v.maxResponseBytes());
        ToolSpec getAsWrite = new ToolSpec(v.description(), v.kind(), v.connectionId(), "GET", v.path(), v.inputSchema(), "WRITE",
                v.timeoutSeconds(), v.maxResponseBytes());

        assertThat(ToolSpecValidator.validate("t", postAsRead))
                .containsExactly("POST tools must have effect WRITE (only GET is treated as read-only)");
        assertThat(ToolSpecValidator.validate("t", getAsWrite)).containsExactly("a GET tool must have effect READ");
    }

    @Test
    void everyPathParameterMustBeDeclared() {
        ToolSpec v = valid();
        ToolSpec spec = new ToolSpec(v.description(), v.kind(), v.connectionId(), v.method(), "/orders/{orderId}/lines/{lineId}",
                v.inputSchema(), v.effect(), v.timeoutSeconds(), v.maxResponseBytes());

        assertThat(ToolSpecValidator.validate("t", spec))
                .containsExactly("path parameter {lineId} is not declared in inputSchema.properties");
    }

    @Test
    void rejectsPathsThatCouldEscapeTheConnectionBaseUrl() {
        ToolSpec v = valid();
        for (String path : List.of("orders", "/../admin", "//evil.example/x", "/x?y=1", "/x#f", "/http://x", "/a\\b", "/a b")) {
            ToolSpec spec = new ToolSpec(v.description(), v.kind(), v.connectionId(), v.method(), path, v.inputSchema(),
                    v.effect(), v.timeoutSeconds(), v.maxResponseBytes());
            assertThat(ToolSpecValidator.validate("t", spec)).as(path).isNotEmpty();
        }
    }

    @Test
    void reportsEveryStructuralProblem() {
        ToolSpec spec = new ToolSpec(" ", "grpc", null, "TRACE", "/x", Map.of("type", "array", "not", "x"), "MAYBE", 0, 10);

        assertThat(ToolSpecValidator.validate(null, spec)).containsExactly(
                "name is required",
                "description is required (it is what the model is told)",
                "connectionId is required",
                "kind must be \"http\", \"mcp\" or \"sandbox\"",
                "method must be one of " + ToolSpecValidator.METHODS,
                "effect must be READ or WRITE",
                "inputSchema uses unsupported keyword \"not\" (supported: " + OutputSchema.KEYWORDS + ")",
                "inputSchema.type must be \"object\"",
                "timeoutSeconds must be between 1 and 120",
                "maxResponseBytes must be between 256 and 1000000");
    }

    @Test
    void hashesLikeAnyOtherPublishedContent() {
        assertThat(ContentHash.of(valid())).isEqualTo(ContentHash.of(ContentHash.read(ContentHash.canonicalJson(valid()), ToolSpec.class)));
    }

    private static ToolSpec write(String idempotency, ToolSpec.Approval approval) {
        return new ToolSpec("Cancel an order", "http", "orders-api", "POST", "/orders/{orderId}/cancel",
                valid().inputSchema(), "WRITE", 10, 4096, idempotency, approval);
    }

    @Test
    void checksIdempotencyAndApprovalSettings() {
        assertThat(ToolSpecValidator.validate("t", write("HEADER", new ToolSpec.Approval(30, 120)))).isEmpty();
        assertThat(ToolSpecValidator.validate("t", write("MAYBE", null))).containsExactly("idempotency must be HEADER or NONE");
        assertThat(ToolSpecValidator.validate("t", write(null, new ToolSpec.Approval(120, 30))))
                .containsExactly("approval.escalateAfterMinutes must not exceed approval.expireAfterMinutes");
        assertThat(ToolSpecValidator.validate("t", write(null, new ToolSpec.Approval(0, 50_000))))
                .containsExactly("approval minutes must be between 1 and 43200");
        ToolSpec v = valid();
        assertThat(ToolSpecValidator.validate("t", new ToolSpec(v.description(), v.kind(), v.connectionId(), v.method(),
                v.path(), v.inputSchema(), v.effect(), v.timeoutSeconds(), v.maxResponseBytes(), null, new ToolSpec.Approval(1, 2))))
                .containsExactly("approval settings apply only to WRITE tools");
    }

    @Test
    void sliceTwoOneToolsKeepTheirCanonicalForm() {
        assertThat(ContentHash.canonicalJson(valid())).doesNotContain("idempotency", "approval");
    }

    static ToolSpec mcp(String fingerprint) {
        return new ToolSpec("Look up an order", "mcp", "orders-mcp", null, null, valid().inputSchema(), "READ", 10, 4096,
                null, null, "lookup_order", fingerprint);
    }

    @Test
    void mcpToolsPinTheRemoteToolAndItsFingerprintInsteadOfAnHttpOperation() {
        assertThat(ToolSpecValidator.validate("t", mcp("sha256:abc"))).isEmpty();
        ToolSpec bad = new ToolSpec("d", "mcp", "orders-mcp", "POST", "/x", valid().inputSchema(), "READ", 10, 4096,
                "HEADER", null, "bad name!", null);
        assertThat(ToolSpecValidator.validate("t", bad)).containsExactly(
                "mcpTool must match " + ToolSpecValidator.MCP_TOOL_NAME.pattern(),
                "mcpFingerprint is required (from discovery)",
                "mcp tools have no method or path",
                "mcp tools have no idempotency key support; idempotency must be NONE");
        ToolSpec v = valid();
        assertThat(ToolSpecValidator.validate("t", new ToolSpec(v.description(), "http", v.connectionId(), v.method(), v.path(),
                v.inputSchema(), v.effect(), v.timeoutSeconds(), v.maxResponseBytes(), null, null, "x", null)))
                .containsExactly("mcpTool and mcpFingerprint apply only to mcp tools");
    }

    @Test
    void fingerprintsAreStableAcrossKeyOrderAndChangeWithTheDefinition() {
        Map<String, Object> a = new java.util.LinkedHashMap<>();
        a.put("type", "object");
        a.put("properties", Map.of("orderId", Map.of("type", "string")));
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("properties", Map.of("orderId", Map.of("type", "string")));
        b.put("type", "object");

        assertThat(McpFingerprint.of("lookup_order", "Look up", a, Map.of("readOnlyHint", true)))
                .isEqualTo(McpFingerprint.of("lookup_order", "Look up", b, Map.of("readOnlyHint", true)))
                .isNotEqualTo(McpFingerprint.of("lookup_order", "Look up and cancel", a, Map.of("readOnlyHint", true)))
                .isNotEqualTo(McpFingerprint.of("lookup_order", "Look up", a, Map.of("readOnlyHint", false)));
    }

    @Test
    void earlierToolShapesKeepTheirHashes() {
        assertThat(ContentHash.canonicalJson(valid())).doesNotContain("mcpTool", "mcpFingerprint");
    }

    static final String IMAGE = "registry.acme/tools/order-report@sha256:" + "a".repeat(64);

    static ToolSpec sandbox(String imageRef) {
        return new ToolSpec("Build an order report", "sandbox", null, null, null, valid().inputSchema(), "READ", 300, 65_536,
                null, null, null, null, "order-report", imageRef);
    }

    @Test
    void sandboxToolsPinADigestAndHaveNoConnection() {
        assertThat(ToolSpecValidator.validate("t", sandbox(IMAGE))).isEmpty();
        assertThat(ToolSpecValidator.validate("t", sandbox("registry.acme/tools/order-report:latest")))
                .containsExactly("sandboxImageRef must be a digest-pinned image reference (name@sha256:<64 hex>)");
        ToolSpec s = sandbox(IMAGE);
        assertThat(ToolSpecValidator.validate("t", new ToolSpec(s.description(), "sandbox", "orders-api", "GET", null,
                s.inputSchema(), "READ", 601, 65_536, null, null, null, null, null, IMAGE))).containsExactly(
                "sandboxImage is required (an enterprise catalog entry)",
                "sandbox tools have no connection, method, path or MCP fields",
                "timeoutSeconds must be between 1 and 600");
        assertThat(ToolSpecValidator.validate("t", new ToolSpec(s.description(), "http", "c", "GET", "/x", s.inputSchema(),
                "READ", 10, 4096, null, null, null, null, "order-report", null)))
                .containsExactly("sandboxImage and sandboxImageRef apply only to sandbox tools");
        assertThat(ContentHash.canonicalJson(valid())).doesNotContain("sandbox");
    }
}
