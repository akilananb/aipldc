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
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger slowRequests = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, ToolStore.Approval> approvals = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, ToolStore.Effect> effects = new java.util.concurrent.ConcurrentHashMap<>();
    private final ToolStore store = mock(ToolStore.class);
    private final List<CallRecord> recorded = new CopyOnWriteArrayList<>();
    private final SecretsPort secrets = ref -> "kv://orders-key".equals(ref) ? SECRET : null;
    private final EgressPolicy egressPolicy = new EgressPolicy(Set.of("localhost"));
    private final ToolExecutor executor = new ToolExecutor(store, secrets, egressPolicy,
            new McpToolCaller(egressPolicy, new McpCredentials(secrets, egressPolicy)), Clock.fixed(NOW, ZoneOffset.UTC));
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotencyKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")));
            String path = exchange.getRequestURI().getRawPath();
            if (path.startsWith("/api/slow") && slowRequests.getAndIncrement() == 0) {
                try {
                    Thread.sleep(2_500); // longer than the tool's 1s timeout: the caller cannot know if it applied
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/api";
        doAnswer(inv -> recorded.add(inv.getArgument(0))).when(store).record(org.mockito.ArgumentMatchers.any());
        fakeApprovalsAndEffects();
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

    /** approvals keyed by turn:callId, effects by idempotency key - as the unique keys in V20 do. */
    private void fakeApprovalsAndEffects() {
        when(store.approval(org.mockito.ArgumentMatchers.any(), anyInt(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(approvals.get(inv.getArgument(1) + ":" + inv.getArgument(2))));
        when(store.requestApproval(org.mockito.ArgumentMatchers.any(), anyString(), anyInt(), anyString(), anyString(), anyInt(),
                anyString(), anyString())).thenAnswer(inv -> approvals.computeIfAbsent(inv.getArgument(2) + ":" + inv.getArgument(3),
                k -> new ToolStore.Approval(UUID.randomUUID(), inv.getArgument(4), inv.getArgument(5), inv.getArgument(7), "PENDING",
                        null, null)));
        when(store.intend(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyString(), anyInt(),
                anyString(), anyString())).thenAnswer(inv -> effects.computeIfAbsent(inv.getArgument(5),
                k -> new ToolStore.Effect(UUID.randomUUID(), k, "INTENDED", 0, null, null, null, null, null)));
        doAnswer(inv -> update(inv.getArgument(0), e -> new ToolStore.Effect(e.id(), e.idempotencyKey(), "SENT",
                e.sendCount() + 1, null, null, e.resolution(), e.resolvedBy(), e.note()))).when(store).markSent(org.mockito.ArgumentMatchers.any());
        doAnswer(inv -> update(inv.getArgument(0), e -> new ToolStore.Effect(e.id(), e.idempotencyKey(), inv.getArgument(1),
                e.sendCount(), inv.getArgument(2), inv.getArgument(3), e.resolution(), e.resolvedBy(), e.note())))
                .when(store).finish(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any(), anyString());
        doAnswer(inv -> update(inv.getArgument(0), e -> "SENT".equals(e.state()) ? new ToolStore.Effect(e.id(), e.idempotencyKey(),
                "UNKNOWN", e.sendCount(), null, null, e.resolution(), e.resolvedBy(), e.note()) : e))
                .when(store).markUnknown(org.mockito.ArgumentMatchers.any());
    }

    private Object update(UUID id, java.util.function.UnaryOperator<ToolStore.Effect> change) {
        effects.replaceAll((k, e) -> e.id().equals(id) ? change.apply(e) : e);
        return null;
    }

    private static ToolSpec writeSpec(String path, String idempotency) {
        ToolSpec s = spec("POST", path, "WRITE", 4096);
        return new ToolSpec(s.description(), s.kind(), s.connectionId(), s.method(), s.path(), s.inputSchema(), s.effect(), 1,
                s.maxResponseBytes(), idempotency, new ToolSpec.Approval(30, 120));
    }

    private void decide(String status, String argsHashOverride) {
        approvals.replaceAll((k, a) -> new ToolStore.Approval(a.id(), a.toolId(), a.toolVersion(),
                argsHashOverride != null ? argsHashOverride : a.argsHash(), status, "reviewer@acme", "looks fine"));
    }

    @Test
    void aWriteWithoutADecidedApprovalPausesTheRunAndSendsNothing() {
        given("cancel-order", writeSpec("/orders/{orderId}/cancel", null));

        ToolExecutor.Outcome outcome = call("cancel-order", "{\"orderId\":\"42\"}");

        assertThat(outcome.pause()).isNotNull();
        assertThat(outcome.pause().kind()).isEqualTo("AWAITING_APPROVAL");
        assertThat(outcome.pause().escalateAfterMinutes()).isEqualTo(30);
        assertThat(outcome.pause().expireAfterMinutes()).isEqualTo(120);
        assertThat(requests).isEmpty();
        assertThat(recorded).singleElement().extracting(CallRecord::decision).isEqualTo("PENDING_APPROVAL");
        assertThat(call("cancel-order", "{\"orderId\":\"42\"}").pause().id()).isEqualTo(outcome.pause().id());
    }

    @Test
    void anApprovedWriteIsSentOnceWithItsIdempotencyKeyAndNeverResent() {
        given("cancel-order", writeSpec("/orders/{orderId}/cancel", "HEADER"));
        call("cancel-order", "{\"orderId\":\"42\"}");
        decide("APPROVED", null);

        ToolExecutor.Outcome first = call("cancel-order", "{\"orderId\":\"42\"}");
        ToolExecutor.Outcome replay = call("cancel-order", "{\"orderId\":\"42\"}");

        assertThat(first.allowed()).isTrue();
        assertThat(first.content()).contains("\"status\":200");
        assertThat(replay.content()).isEqualTo(first.content());
        assertThat(requests).containsExactly("POST /api/orders/42/cancel");
        assertThat(idempotencyKeys).containsExactly(RUN + ":1:call-1");
        assertThat(effects.values()).singleElement().satisfies(e -> {
            assertThat(e.state()).isEqualTo("SUCCEEDED");
            assertThat(e.sendCount()).isEqualTo(1);
        });
        assertThat(recorded).extracting(CallRecord::error).last().asString().contains("not resent");
    }

    @Test
    void anApprovalForOtherArgumentsOrARejectionAuthorizesNothing() {
        given("cancel-order", writeSpec("/orders/{orderId}/cancel", null));
        call("cancel-order", "{\"orderId\":\"42\"}");

        decide("APPROVED", "sha256:other-args");
        assertThat(call("cancel-order", "{\"orderId\":\"42\"}").content()).contains("different arguments");
        decide("REJECTED", null);
        assertThat(call("cancel-order", "{\"orderId\":\"42\"}").content()).contains("rejected by reviewer@acme: looks fine");
        decide("EXPIRED", null);
        assertThat(call("cancel-order", "{\"orderId\":\"42\"}").content()).contains("expired without a decision");
        assertThat(requests).isEmpty();
    }

    @Test
    void aTimedOutWriteIsResentWithTheSameKeyWhenTheTargetDedups() {
        given("slow-cancel", writeSpec("/slow/{orderId}", "HEADER"));
        call("slow-cancel", "{\"orderId\":\"42\"}");
        decide("APPROVED", null);

        ToolExecutor.Outcome outcome = call("slow-cancel", "{\"orderId\":\"42\"}");

        assertThat(outcome.allowed()).isTrue();
        assertThat(requests).hasSize(2);
        assertThat(idempotencyKeys).containsExactly(RUN + ":1:call-1", RUN + ":1:call-1");
        assertThat(effects.values()).singleElement().satisfies(e -> {
            assertThat(e.state()).isEqualTo("SUCCEEDED");
            assertThat(e.sendCount()).isEqualTo(2);
        });
    }

    @Test
    void aTimedOutWriteWithoutIdempotencySupportWaitsForAnOperatorAndIsNotResent() {
        given("slow-cancel", writeSpec("/slow/{orderId}", null));
        call("slow-cancel", "{\"orderId\":\"42\"}");
        decide("APPROVED", null);

        ToolExecutor.Outcome outcome = call("slow-cancel", "{\"orderId\":\"42\"}");
        ToolExecutor.Outcome again = call("slow-cancel", "{\"orderId\":\"42\"}");

        assertThat(outcome.pause().kind()).isEqualTo("NEEDS_OPERATOR");
        assertThat(again.pause().id()).isEqualTo(outcome.pause().id());
        assertThat(requests).hasSize(1);
        assertThat(idempotencyKeys).containsExactly("null");
        assertThat(effects.values()).singleElement().extracting(ToolStore.Effect::state).isEqualTo("UNKNOWN");
    }

    @Test
    void anOperatorResolvedEffectIsReportedToTheModelWithoutResending() {
        given("cancel-order", writeSpec("/orders/{orderId}/cancel", null));
        call("cancel-order", "{\"orderId\":\"42\"}");
        decide("APPROVED", null);
        String key = RUN + ":1:call-1";
        effects.put(key, new ToolStore.Effect(UUID.randomUUID(), key, "SUCCEEDED", 1, null, null, "SUCCEEDED", "ops@acme",
                "confirmed in the orders console"));

        ToolExecutor.Outcome outcome = call("cancel-order", "{\"orderId\":\"42\"}");

        assertThat(outcome.content()).contains("recorded by operator ops@acme: confirmed in the orders console");
        assertThat(requests).isEmpty();
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
