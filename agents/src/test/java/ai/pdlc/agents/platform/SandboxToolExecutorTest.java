package ai.pdlc.agents.platform;

import ai.pdlc.adapters.sandbox.SandboxEgressProxy;
import ai.pdlc.agents.platform.ToolStore.CallRecord;
import ai.pdlc.agents.platform.ToolStore.PinnedTool;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.platform.ToolArgs;
import ai.pdlc.core.platform.ToolSpec;
import ai.pdlc.core.port.SandboxPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sandbox tools through the single policy path (docs/phase-2-execution-spec.md slice 2.4), over a fake {@link SandboxPort}. */
class SandboxToolExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000d01");
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final String REF = "registry.acme/tools/order-report@sha256:" + "a".repeat(64);
    private static final Map<String, Object> INPUT = Map.of("type", "object",
            "properties", Map.of("orderId", Map.of("type", "string")), "required", List.of("orderId"));
    private static final String OUTPUT = "{\"type\":\"object\",\"properties\":{\"report\":{\"type\":\"string\"}},\"required\":[\"report\"]}";

    /** Records requests and answers with whatever the test scripts; checks the credential while "running". */
    final class FakePort implements SandboxPort {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final List<String> terminated = new CopyOnWriteArrayList<>();
        String isolation = "runtimeClass gvisor";
        Function<Request, Result> behavior = r -> new Result(0, "{\"report\":\"ok\"}", false, false, null);
        Boolean tokenLiveDuringRun;

        @Override
        public Result run(Request request) {
            requests.add(request);
            if (request.proxyUrl() != null) {
                tokenLiveDuringRun = proxy.isLive(token(request));
            }
            return behavior.apply(request);
        }

        @Override
        public void terminateRun(String runId) {
            terminated.add(runId);
        }

        @Override
        public String isolation() {
            return isolation;
        }
    }

    private final ToolStore store = mock(ToolStore.class);
    private final List<CallRecord> recorded = new CopyOnWriteArrayList<>();
    private final FakePort port = new FakePort();
    private SandboxEgressProxy proxy;
    private SandboxToolRunner runner;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        proxy = new SandboxEgressProxy("127.0.0.1", 0, new EgressPolicy(Set.of())::check, e -> runner.onEgress(e));
        runner = new SandboxToolRunner(port, proxy, "pdlc-sandbox-relay", 3128);
        executor = new ToolExecutor(store, ref -> null, new EgressPolicy(Set.of()), null, runner, Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(inv -> recorded.add(inv.getArgument(0))).when(store).record(any());
        image("ACTIVE", REF, List.of("api.example"));
    }

    @AfterEach
    void tearDown() throws Exception {
        runner.close();
    }

    private void image(String status, String ref, List<String> hosts) throws Exception {
        when(store.sandboxImage("order-report")).thenReturn(Optional.of(new ToolStore.SandboxImage("order-report", ref, status,
                JSON.writeValueAsString(INPUT), OUTPUT, JSON.writeValueAsString(hosts), 500, 256, 30)));
    }

    private static ToolSpec tool(String effect) {
        return new ToolSpec("Build an order report", "sandbox", null, null, null, INPUT, effect, 60, 4096,
                null, null, null, null, "order-report", REF);
    }

    private void pin(ToolSpec spec) {
        when(store.load("ops", "report", 1)).thenReturn(Optional.of(new PinnedTool("ops", "report", 1, "Report",
                ContentHash.canonicalJson(spec), ContentHash.ofTool("Report", spec), "ACTIVE", null, null, null, null, null, null,
                null, false, null)));
    }

    private ToolExecutor.Outcome call(int attempt) {
        return executor.execute(new ToolExecutor.Context(RUN, "ops", attempt, 1, NOW.plusSeconds(600)), Map.of("report", 1),
                new ModelInvoker.ToolCall("call-1", "report", "{\"orderId\":\"42\"}"));
    }

    private static String token(SandboxPort.Request r) {
        return r.proxyUrl().substring("http://pdlc:".length(), r.proxyUrl().indexOf('@'));
    }

    @Test
    void anApprovedImageRunsWithItsLimitsAndAShortLivedCredentialScopedToIt() throws Exception {
        pin(tool("READ"));
        port.behavior = r -> {
            runner.onEgress(new SandboxEgressProxy.Event(r.callKey(), "api.example", 443, true, null));
            runner.onEgress(new SandboxEgressProxy.Event(r.callKey(), "evil.example", 443, false, "not approved"));
            return new SandboxPort.Result(0, "{\"report\":\"order 42 via " + token(r) + "\"}", false, false, null);
        };

        ToolExecutor.Outcome outcome = call(1);

        assertThat(outcome.allowed()).isTrue();
        SandboxPort.Request request = port.requests.get(0);
        assertThat(request.imageRef()).isEqualTo(REF);
        assertThat(request.inputJson()).isEqualTo("{\"orderId\":\"42\"}");
        assertThat(request.cpuMillis()).isEqualTo(500);
        assertThat(request.memoryMb()).isEqualTo(256);
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(30)); // the image's limit beats the tool's 60s
        assertThat(request.callKey()).isEqualTo(RUN + ":1:call-1");
        assertThat(request.proxyUrl()).startsWith("http://pdlc:").endsWith("@pdlc-sandbox-relay:3128");
        assertThat(port.tokenLiveDuringRun).isTrue();
        assertThat(proxy.isLive(token(request))).isFalse();
        JsonNode content = JSON.readTree(outcome.content());
        assertThat(content.get("exitCode").asInt()).isZero();
        assertThat(content.get("output").get("report").asText()).isEqualTo("order 42 via [REDACTED]");
        assertThat(recorded).singleElement().satisfies(r -> {
            assertThat(r.decision()).isEqualTo("ALLOWED");
            assertThat(r.reason()).isEqualTo("egress: api.example:443 allowed, evil.example:443 denied");
            assertThat(r.error()).isNull();
        });
    }

    @Test
    void anImageWithoutApprovedHostsGetsNoNetworkAtAll() throws Exception {
        image("ACTIVE", REF, List.of());
        pin(tool("READ"));

        call(1);

        assertThat(port.requests.get(0).proxyUrl()).isNull();
    }

    @Test
    void withoutAnIsolationRuntimeNothingRuns() {
        port.isolation = null;
        pin(tool("READ"));

        assertThat(call(1).content()).contains("no sandbox isolation runtime is configured; untrusted packages are refused");
        assertThat(port.requests).isEmpty();

        ToolExecutor unconfigured = new ToolExecutor(store, ref -> null, new EgressPolicy(Set.of()), null, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(unconfigured.execute(new ToolExecutor.Context(RUN, "ops", 1, 1, NOW.plusSeconds(60)), Map.of("report", 1),
                new ModelInvoker.ToolCall("c", "report", "{\"orderId\":\"1\"}")).content()).contains("untrusted packages are refused");
    }

    @Test
    void aRetiredChangedOrMissingCatalogEntryIsRefusedWithoutRunning() throws Exception {
        pin(tool("READ"));

        image("RETIRED", REF, List.of());
        assertThat(call(1).content()).contains("sandbox image order-report is retired");
        image("ACTIVE", "registry.acme/tools/order-report@sha256:" + "b".repeat(64), List.of());
        assertThat(call(2).content()).contains("changed since the tool was reviewed; it needs re-review");
        when(store.sandboxImage("order-report")).thenReturn(Optional.of(new ToolStore.SandboxImage("order-report", REF, "ACTIVE",
                "{\"type\":\"object\"}", OUTPUT, "[]", 500, 256, 30)));
        assertThat(call(3).content()).contains("declares a different input schema now");
        when(store.sandboxImage("order-report")).thenReturn(Optional.empty());
        assertThat(call(4).content()).contains("is not in the enterprise catalog");

        assertThat(port.requests).isEmpty();
        assertThat(recorded).extracting(CallRecord::decision).containsOnly("DENIED");
    }

    @Test
    void aPackageMustExitCleanlyAndPrintJsonMatchingItsOutputSchema() {
        pin(tool("READ"));

        port.behavior = r -> new SandboxPort.Result(3, "boom", false, false, null);
        assertThat(call(1).content()).contains("\"error\":\"the package exited with code 3\"").contains("\"stdout\":\"boom\"");
        port.behavior = r -> new SandboxPort.Result(0, "not json", false, false, null);
        assertThat(call(2).content()).contains("the package's output is not JSON");
        port.behavior = r -> new SandboxPort.Result(0, "{\"summary\":1}", false, false, null);
        assertThat(call(3).content()).contains("does not match the image's output schema");
        port.behavior = r -> new SandboxPort.Result(0, "{\"report\":\"x", true, false, null);
        assertThat(call(4).content()).contains("exceeded maxResponseBytes");
        assertThat(port.requests.get(0).maxOutputBytes()).isEqualTo(4096);
    }

    @Test
    void aTimedOutReadIsAToolErrorAndItsCredentialIsRevoked() {
        pin(tool("READ"));
        port.behavior = r -> new SandboxPort.Result(-1, "", false, true, null);

        ToolExecutor.Outcome outcome = call(1);

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.content()).contains("timed out after 30s; the sandbox was killed");
        assertThat(proxy.isLive(token(port.requests.get(0)))).isFalse();
    }

    @Test
    void aSandboxWriteWaitsForApprovalRunsOnceAndAnUnknownOutcomeGoesToAnOperator() {
        ToolSpec write = tool("WRITE");
        pin(write);
        UUID approval = UUID.randomUUID();
        when(store.approval(any(), anyInt(), anyString())).thenReturn(Optional.empty());
        when(store.requestApproval(any(), anyString(), anyInt(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new ToolStore.Approval(approval, "report", 1, "h", "PENDING", null, null));

        assertThat(call(1).pause().kind()).isEqualTo(ToolExecutor.AWAITING_APPROVAL);
        assertThat(port.requests).isEmpty();

        String hash = ToolArgs.parse("{\"orderId\":\"42\"}", INPUT).hash();
        when(store.approval(any(), anyInt(), anyString()))
                .thenReturn(Optional.of(new ToolStore.Approval(approval, "report", 1, hash, "APPROVED", "rev@acme", null)));
        UUID effect = UUID.randomUUID();
        when(store.effect(anyString())).thenReturn(Optional.empty());
        when(store.intend(any(), any(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new ToolStore.Effect(effect, "k", "INTENDED", 0, null, null, null, null, null));

        assertThat(call(2).allowed()).isTrue();
        assertThat(port.requests).hasSize(1);
        verify(store).markSent(effect);
        verify(store).finish(eq(effect), eq("SUCCEEDED"), isNull(), anyString());

        UUID second = UUID.randomUUID();
        when(store.intend(any(), any(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new ToolStore.Effect(second, "k2", "INTENDED", 0, null, null, null, null, null));
        port.behavior = r -> new SandboxPort.Result(-1, "", false, true, null);
        ToolExecutor.Outcome unknown = call(3);
        assertThat(unknown.pause().kind()).isEqualTo(ToolExecutor.NEEDS_OPERATOR);
        verify(store).markUnknown(second);
        verify(store, never()).finish(eq(second), anyString(), any(), anyString());

        when(store.effect(anyString())).thenReturn(Optional.of(new ToolStore.Effect(second, "k2", "UNKNOWN", 1, null, null,
                null, null, null)));
        assertThat(call(4).pause().kind()).isEqualTo(ToolExecutor.NEEDS_OPERATOR);
        assertThat(port.requests).hasSize(2);
    }

    @Test
    void cancellingTheRunRevokesItsCredentialsAndKillsItsSandboxes() {
        SandboxEgressProxy.Grant grant = proxy.issue(RUN.toString(), RUN + ":1:call-9", Set.of("api.example"), Duration.ofMinutes(1));

        executor.cancelRun(RUN.toString());

        assertThat(proxy.isLive(grant.token())).isFalse();
        assertThat(port.terminated).containsExactly(RUN.toString());
    }
}
