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
        ToolSpec spec = new ToolSpec(" ", "mcp", null, "TRACE", "/x", Map.of("type", "array", "pattern", "x"), "MAYBE", 0, 10);

        assertThat(ToolSpecValidator.validate(null, spec)).containsExactly(
                "name is required",
                "description is required (it is what the model is told)",
                "kind must be \"http\"",
                "connectionId is required",
                "method must be one of " + ToolSpecValidator.METHODS,
                "effect must be READ or WRITE",
                "inputSchema uses unsupported keyword \"pattern\" (supported: " + OutputSchema.KEYWORDS + ")",
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
}
