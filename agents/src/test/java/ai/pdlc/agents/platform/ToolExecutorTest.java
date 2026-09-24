package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.ToolStore.CallRecord;
import ai.pdlc.agents.platform.ToolStore.PinnedTool;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.platform.ToolSpec;
import ai.pdlc.core.port.SecretsPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The single tool policy path against a real local HTTP API (docs/phase-2-execution-spec.md slice
 * 2.1): allowed READ calls, every denial happening before any HTTP request, no redirect following,
 * truncation, and the credential never reaching the model.
 */
class ToolExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final String SECRET = "sk-orders-secret-123";
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final ToolStore store = mock(ToolStore.class);
    private final List<CallRecord> recorded = new CopyOnWriteArrayList<>();
    private final SecretsPort secrets = ref -> "kv://orders-key".equals(ref) ? SECRET : null;
    private final ToolExecutor executor = new ToolExecutor(store, secrets,
            new EgressPolicy(Set.of("localhost")), Clock.fixed(NOW, ZoneOffset.UTC));
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getRawPath();
            if (path.startsWith("/api/redirect")) {
                exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/iam");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            String body = path.startsWith("/api/big") ? "x".repeat(1000)
                    : "{\"order\":\"shipped\",\"echo\":\"" + exchange.getRequestHeaders().getFirst("Authorization") + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api";
        doAnswer(inv -> recorded.add(inv.getArgument(0))).when(store).record(org.mockito.ArgumentMatchers.any());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static ToolSpec spec(String method, String path, String effect, int maxBytes) {
        return new ToolSpec("Look up an order", "http", "orders-api", method, path,
                Map.of("type", "object", "properties", Map.of("orderId", Map.of("type", "string"),
                        "expand", Map.of("type", "boolean")), "required", List.of("orderId")),
                effect, 5, maxBytes);
    }

    private void given(String toolId, ToolSpec spec, String toolStatus, String connectionStatus, OffsetDateTime expires,
                       boolean granted, String url) {
        when(store.load("engineering", toolId, 1)).thenReturn(Optional.of(new PinnedTool("engineering", toolId, 1,
                "Tool", ContentHash.canonicalJson(spec), ContentHash.ofTool("Tool", spec), toolStatus, "orders-api",
                "HTTP_API", connectionStatus, expires, "API_KEY", "kv://orders-key", url, granted)));
    }

    private void given(String toolId, ToolSpec spec) {
        given(toolId, spec, "ACTIVE", "ACTIVE", null, true, baseUrl);
    }

    private ToolExecutor.Outcome call(String toolId, String args) {
        return executor.execute(new ToolExecutor.Context(RUN, "engineering", 1, 1, NOW.plusSeconds(60)),
                Map.of(toolId, 1), new ModelInvoker.ToolCall("call-1", toolId, args));
    }

    @Test
    void anAllowedReadCallsTheApiWithTheCredentialButNeverShowsItToTheModel() throws Exception {
        given("get-order", spec("GET", "/orders/{orderId}", "READ", 4096));

        ToolExecutor.Outcome outcome = call("get-order", "{\"orderId\":\"o 42/../x\",\"expand\":true}");

        assertThat(outcome.allowed()).isTrue();
        assertThat(requests).containsExactly("GET /api/orders/o%2042%2F..%2Fx?expand=true");
        assertThat(authorizations).containsExactly("Bearer " + SECRET);
        JsonNode content = JSON.readTree(outcome.content());
        assertThat(content.get("status").asInt()).isEqualTo(200);
        assertThat(content.get("body").asText()).contains("shipped").contains("[REDACTED]").doesNotContain(SECRET);
        assertThat(recorded).singleElement().satisfies(r -> {
            assertThat(r.decision()).isEqualTo("ALLOWED");
            assertThat(r.httpStatus()).isEqualTo(200);
            assertThat(r.argsHash()).startsWith("sha256:");
            assertThat(r.toString()).doesNotContain(SECRET);
        });
    }

    @Test
    void aWriteToolIsDeniedBeforeAnyHttpRequest() {
        given("cancel-order", spec("POST", "/orders/{orderId}/cancel", "WRITE", 4096));

        ToolExecutor.Outcome outcome = call("cancel-order", "{\"orderId\":\"42\"}");

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.content()).contains("requires an approval");
        assertThat(requests).isEmpty();
        assertThat(recorded).singleElement().extracting(CallRecord::decision).isEqualTo("DENIED");
    }

    @Test
    void aToolTheAgentDidNotPinIsDeniedWhateverTheModelClaims() {
        given("get-order", spec("GET", "/orders/{orderId}", "READ", 4096));

        ToolExecutor.Outcome outcome = executor.execute(new ToolExecutor.Context(RUN, "engineering", 1, 1, NOW.plusSeconds(60)),
                Map.of("get-order", 1), new ModelInvoker.ToolCall("c", "delete-everything", "{}"));

        assertThat(outcome.content()).contains("not available to this agent");
        assertThat(requests).isEmpty();
    }

    @Test
    void invalidArgumentsAreDeniedWithTheFindings() {
        given("get-order", spec("GET", "/orders/{orderId}", "READ", 4096));

        assertThat(call("get-order", "{\"expand\":true,\"admin\":1}").content())
                .contains("invalid arguments").contains("$.admin is not a declared argument");
        assertThat(call("get-order", "{\"orderId\":\"..\"}").content()).contains("not a valid path segment");
        assertThat(call("get-order", "not json").content()).contains("arguments are not valid JSON");
        assertThat(requests).isEmpty();
    }

    @Test
    void revokedUngrantedOrExpiredConnectionsAndRetiredToolsAreDeniedBeforeHttp() {
        ToolSpec s = spec("GET", "/orders/{orderId}", "READ", 4096);
        given("t", s, "ACTIVE", "ACTIVE", null, false, baseUrl);
        assertThat(call("t", "{\"orderId\":\"1\"}").content()).contains("not granted to workspace engineering");
        given("t", s, "ACTIVE", "REVOKED", null, true, baseUrl);
        assertThat(call("t", "{\"orderId\":\"1\"}").content()).contains("is revoked");
        given("t", s, "ACTIVE", "ACTIVE", OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC), true, baseUrl);
        assertThat(call("t", "{\"orderId\":\"1\"}").content()).contains("expired");
        given("t", s, "RETIRED", "ACTIVE", null, true, baseUrl);
        assertThat(call("t", "{\"orderId\":\"1\"}").content()).contains("retired");

        assertThat(requests).isEmpty();
        assertThat(recorded).extracting(CallRecord::decision).containsOnly("DENIED").hasSize(4);
    }

    @Test
    void theMetadataServiceIsNotReachableDirectlyOrThroughARedirect() throws Exception {
        given("meta", spec("GET", "/orders/{orderId}", "READ", 4096), "ACTIVE", "ACTIVE", null, true,
                "http://169.254.169.254/latest");
        assertThat(call("meta", "{\"orderId\":\"1\"}").content()).contains("destination not allowed").contains("metadata");

        given("hop", spec("GET", "/redirect/{orderId}", "READ", 4096));
        ToolExecutor.Outcome outcome = call("hop", "{\"orderId\":\"1\"}");

        assertThat(requests).containsExactly("GET /api/redirect/1");
        JsonNode content = JSON.readTree(outcome.content());
        assertThat(content.get("status").asInt()).isEqualTo(302);
        assertThat(content.get("error").asText()).isEqualTo("redirect not followed");
        assertThat(recorded.get(1).error()).isEqualTo("redirect not followed");
    }

    @Test
    void largeResponsesAreTruncatedAndFlagged() throws Exception {
        given("big", spec("GET", "/big/{orderId}", "READ", 256));

        JsonNode content = JSON.readTree(call("big", "{\"orderId\":\"1\"}").content());

        assertThat(content.get("truncated").asBoolean()).isTrue();
        assertThat(content.get("body").asText()).hasSize(256);
        assertThat(recorded.get(0).truncated()).isTrue();
        assertThat(recorded.get(0).responseBytes()).isEqualTo(256L);
    }

    @Test
    void aTamperedToolVersionOrAnUnresolvableCredentialIsDenied() {
        ToolSpec s = spec("GET", "/orders/{orderId}", "READ", 4096);
        when(store.load(anyString(), anyString(), anyInt())).thenReturn(Optional.of(new PinnedTool("engineering", "t", 1,
                "Tool", ContentHash.canonicalJson(s), "sha256:bogus", "ACTIVE", "orders-api", "HTTP_API", "ACTIVE", null,
                "API_KEY", "kv://orders-key", baseUrl, true)));
        assertThat(call("t", "{\"orderId\":\"1\"}").content()).contains("does not match its hash");

        when(store.load(anyString(), anyString(), anyInt())).thenReturn(Optional.of(new PinnedTool("engineering", "t", 1,
                "Tool", ContentHash.canonicalJson(s), ContentHash.ofTool("Tool", s), "ACTIVE", "orders-api", "HTTP_API",
                "ACTIVE", null, "API_KEY", "kv://other-key", baseUrl, true)));
        assertThat(call("t", "{\"orderId\":\"1\"}").content()).contains("credential for connection orders-api could not be resolved");
        assertThat(requests).isEmpty();
    }

    @Test
    void noCallStartsAfterTheRunDeadline() {
        given("get-order", spec("GET", "/orders/{orderId}", "READ", 4096));

        ToolExecutor.Outcome outcome = executor.execute(new ToolExecutor.Context(RUN, "engineering", 1, 3, NOW),
                Map.of("get-order", 1), new ModelInvoker.ToolCall("c", "get-order", "{\"orderId\":\"1\"}"));

        assertThat(outcome.content()).contains("deadline");
        assertThat(requests).isEmpty();
    }
}
