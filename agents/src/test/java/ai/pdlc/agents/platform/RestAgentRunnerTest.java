package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.A2aRunnerTest.FakeRuns;
import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.agents.platform.RunStore.RemoteTask;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.workflow.AgentRunWorkflow.AgentRunOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link RestAgentRunner} against an in-process HTTP agent service with sync and async (job) endpoints. */
class RestAgentRunnerTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-00000000e571");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The service. {@code kind} input: done (after 2 polls), error, paused (an undeclared state), stuck, pinned (not cancelable). */
    final class Service {
        final HttpServer server;
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final AtomicInteger processed = new AtomicInteger();
        final Map<String, String> byKey = new ConcurrentHashMap<>();
        final Map<String, String> kinds = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> polls = new ConcurrentHashMap<>();
        final Map<String, String> states = new ConcurrentHashMap<>();
        volatile int syncStatus = 200;
        volatile boolean dropAfterProcessing;

        Service() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/summarise", this::sync);
            server.createContext("/jobs", this::jobs);
            server.start();
        }

        String base() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        private void sync(HttpExchange ex) throws IOException {
            JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            calls.add("POST /summarise " + body.path("inputs").path("input").asText() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            keys.add(String.valueOf(key));
            String stored = key == null ? null : byKey.get(key);
            if (stored == null) {
                processed.incrementAndGet();
                stored = "{\"summary\":\"Summary of " + body.path("inputs").path("input").asText() + "\",\"error\":\"bad input\"}";
                if (key != null) {
                    byKey.put(key, stored);
                }
                if (dropAfterProcessing) {
                    dropAfterProcessing = false;
                    ex.close(); // processed, but the answer never arrives
                    return;
                }
            }
            respond(ex, syncStatus, stored);
        }

        private void jobs(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            calls.add(method + " " + path);
            if (method.equals("POST") && path.equals("/jobs")) {
                JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
                String id = "job-" + (kinds.size() + 1);
                kinds.put(id, body.path("inputs").path("input").asText());
                polls.put(id, new AtomicInteger());
                states.put(id, "queued");
                respond(ex, 202, "{\"job\":{\"id\":\"" + id + "\",\"state\":\"queued\"}}");
                return;
            }
            String id = path.split("/")[2];
            if (!kinds.containsKey(id)) {
                respond(ex, 404, "{}");
                return;
            }
            if (path.endsWith("/cancel")) {
                if (kinds.get(id).equals("pinned")) {
                    respond(ex, 409, "{\"job\":{\"error\":\"cannot cancel\"}}");
                } else {
                    states.put(id, "cancelled");
                    respond(ex, 200, job(id));
                }
                return;
            }
            int n = polls.get(id).incrementAndGet();
            if (states.get(id).equals("queued") || states.get(id).equals("running")) {
                switch (kinds.get(id)) {
                    case "done" -> states.put(id, n >= 2 ? "done" : "running");
                    case "error" -> states.put(id, "error");
                    case "paused" -> states.put(id, "paused");
                    default -> states.put(id, "running");
                }
            }
            respond(ex, 200, job(id));
        }

        private String job(String id) {
            String state = states.get(id);
            return "{\"job\":{\"id\":\"" + id + "\",\"state\":\"" + state + "\""
                    + (state.equals("done") ? ",\"result\":{\"report\":\"Report " + id + "\"}" : "")
                    + (state.equals("error") ? ",\"error\":\"ledger unavailable\"" : "") + "}}";
        }

        private void respond(HttpExchange ex, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }
    }

    private Service service;
    private final FakeRuns runs = new FakeRuns();
    private final EgressPolicy egress = new EgressPolicy(Set.of("localhost"));
    private final McpCredentials credentials = new McpCredentials(ref -> "kv://svc-key".equals(ref) ? "svc-secret" : null, egress);
    private RestAgentRunner runner = new RestAgentRunner(runs, credentials, egress, Clock.systemUTC(), Duration.ofMillis(10));

    @BeforeEach
    void start() throws IOException {
        service = new Service();
    }

    @AfterEach
    void stop() {
        service.server.stop(0);
    }

    static AgentSpec.RestBinding sync(String idempotency) {
        return new AgentSpec.RestBinding("report-service", "sync", new AgentSpec.Endpoint("POST", "/summarise"), null, null, null,
                null, null, "/summary", "/error", null, idempotency);
    }

    static AgentSpec.RestBinding async(boolean cancellable) {
        return new AgentSpec.RestBinding("report-service", "async", new AgentSpec.Endpoint("POST", "/jobs"),
                new AgentSpec.Endpoint("GET", "/jobs/{taskId}"), cancellable ? new AgentSpec.Endpoint("POST", "/jobs/{taskId}/cancel") : null,
                "/job/id", "/job/state", Map.of("queued", "WORKING", "running", "WORKING", "done", "COMPLETED", "error", "FAILED",
                        "cancelled", "CANCELED"), "/job/result", "/job/error", 1, "NONE");
    }

    private AgentSpec spec(AgentSpec.RestBinding binding, int timeoutSeconds, Map<String, Object> outputSchema) {
        return new AgentSpec("Calls the service", "rest", null, List.of(new AgentSpec.Variable("input", null, true)), null,
                new AgentSpec.Limits(null, timeoutSeconds), outputSchema, null, null, binding);
    }

    private AgentRunOutcome invoke(AgentSpec spec, String input) {
        runs.invocation = new Invocation(RUN, "engineering", "RUNNING", "svc", 1, ContentHash.ofAgent("Svc", spec), "Svc",
                ContentHash.canonicalJson(spec), Map.of("input", input), null, null, null, "report-service", "ACTIVE", null,
                "API_KEY", "kv://svc-key", service.base(), 0, 0, "REST_AGENT", null, true);
        return runner.invoke(runs.invocation, spec, null);
    }

    @Test
    void aSyncCallReturnsTheDeclaredResult() {
        AgentRunOutcome outcome = invoke(spec(sync(null), 30, null), "Q3");

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(runs.output).isEqualTo("Summary of Q3");
        assertThat(runs.sends).containsEntry(RUN + ":0", "ACKED");
        assertThat(service.calls).containsExactly("POST /summarise Q3 auth=Bearer svc-secret");
        assertThat(runs.remote.dialect()).isEqualTo("rest-sync");
    }

    @Test
    void aRefusedSyncCallFailsWithTheServicesMessage() {
        service.syncStatus = 422;

        assertThatThrownBy(() -> invoke(spec(sync(null), 30, null), "Q3")).isInstanceOfSatisfying(ApplicationFailure.class,
                f -> assertThat(f.isNonRetryable()).isTrue());
        assertThat(runs.error).isEqualTo("the service refused the request: HTTP 422: bad input");
    }

    @Test
    void anUnansweredSubmitIsNotResentWithoutIdempotencySupport() {
        service.dropAfterProcessing = true;

        assertThatThrownBy(() -> invoke(spec(sync("NONE"), 30, null), "Q3")).isInstanceOfSatisfying(ApplicationFailure.class,
                f -> assertThat(f.isNonRetryable()).isTrue());
        assertThat(runs.error).startsWith("outcome unknown").contains("was not resent");
        assertThat(service.calls).hasSize(1);
        assertThatThrownBy(() -> invoke(spec(sync("NONE"), 30, null), "Q3")).isInstanceOf(ApplicationFailure.class);
        assertThat(service.calls).hasSize(1);
    }

    @Test
    void withIdempotencyKeySupportTheSameRequestIsResentAndDeduplicated() {
        service.dropAfterProcessing = true;
        AgentSpec spec = spec(sync("HEADER"), 30, null);

        assertThatThrownBy(() -> invoke(spec, "Q3")).isInstanceOfSatisfying(ApplicationFailure.class,
                f -> assertThat(f.isNonRetryable()).isFalse());
        AgentRunOutcome retried = invoke(spec, "Q3");

        assertThat(retried.status()).isEqualTo("SUCCEEDED");
        assertThat(service.keys).containsExactly(RUN + ":0", RUN + ":0");
        assertThat(service.processed).hasValue(1);
    }

    @Test
    void anAsyncJobIsPolledUntilTheMappingSaysItCompleted() {
        AgentRunOutcome outcome = invoke(spec(async(true), 30, Map.of("type", "object")), "done");

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(runs.output).isEqualTo("{\"report\":\"Report job-1\"}");
        assertThat(runs.remote).isEqualTo(new RemoteTask("rest-async", "job-1", null, "COMPLETED", "done", null));
        assertThat(service.calls).containsExactly("POST /jobs", "GET /jobs/job-1", "GET /jobs/job-1");
    }

    @Test
    void aRemoteFailureAndAnUndeclaredStateFailTheRunWithoutGuessing() {
        AgentRunOutcome failed = invoke(spec(async(true), 30, null), "error");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(runs.error).isEqualTo("the service reported the job failed: ledger unavailable");

        runs.remote = null;
        runs.sends.clear();
        runs.status = "RUNNING";
        AgentRunOutcome paused = invoke(spec(async(true), 30, null), "paused");
        assertThat(paused.status()).isEqualTo("FAILED");
        assertThat(runs.error).isEqualTo("the service reported state 'paused', which the agent's mapping does not declare; it is not guessed");
        assertThat(runs.remote.statusText()).isEqualTo("paused");
    }

    @Test
    void aRetryFollowsTheSavedJobInsteadOfSubmittingAgain() {
        invokeThenForget("done");
        service.calls.clear();

        assertThat(invoke(spec(async(true), 30, null), "done").status()).isEqualTo("SUCCEEDED");

        assertThat(service.calls).noneMatch(c -> c.equals("POST /jobs"));
    }

    @Test
    void cancellationIsRecordedAsTheServiceAnswered() {
        invokeThenForget("stuck");
        runner.cancelRemote(RUN.toString());
        assertThat(runs.remote.cancel()).isEqualTo("ACKNOWLEDGED");

        runs.remote = null;
        runs.sends.clear();
        invokeThenForget("pinned");
        runner.cancelRemote(RUN.toString());
        assertThat(runs.remote.cancel()).isEqualTo("REFUSED");

        runs.remote = new RemoteTask("rest-async", "job-2", null, "WORKING", "running", null);
        runs.invocation = withSpec(spec(async(false), 30, null));
        runner.cancelRemote(RUN.toString());
        assertThat(runs.remote.cancel()).isEqualTo("UNSUPPORTED");

        runs.remote = new RemoteTask("rest-sync", null, null, "WORKING", null, null);
        runner.cancelRemote(RUN.toString());
        assertThat(runs.remote.cancel()).isEqualTo("UNSUPPORTED");
    }

    @Test
    void runningOutOfTimeCancelsTheJobAndFails() {
        AgentRunOutcome outcome = invoke(spec(async(true), 1, null), "stuck");

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(runs.error).isEqualTo("the job did not finish within the agent's time; cancel: acknowledged");
        assertThat(service.calls).contains("POST /jobs/job-1/cancel");
    }

    @Test
    void anEgressBlockedServiceIsNeverCalledAndTheOutputSchemaIsEnforced() {
        runner = new RestAgentRunner(runs, credentials, new EgressPolicy(Set.of()), Clock.systemUTC(), Duration.ofMillis(10));
        assertThatThrownBy(() -> invoke(spec(sync(null), 30, null), "Q3")).isInstanceOfSatisfying(ApplicationFailure.class,
                f -> assertThat(f.getOriginalMessage()).contains("destination not allowed"));
        assertThat(service.calls).isEmpty();

        runner = new RestAgentRunner(runs, credentials, egress, Clock.systemUTC(), Duration.ofMillis(10));
        runs.sends.clear();
        assertThatThrownBy(() -> invoke(spec(sync(null), 30, Map.of("type", "object")), "Q3"))
                .isInstanceOfSatisfying(ApplicationFailure.class, f -> assertThat(f.getOriginalMessage()).contains("outputSchema"));
    }

    @Test
    void theConnectionIsRecheckedAtRunTime() {
        AgentSpec spec = spec(sync(null), 30, null);
        runs.invocation = new Invocation(RUN, "engineering", "RUNNING", "svc", 1, "h", "Svc", ContentHash.canonicalJson(spec),
                Map.of("input", "Q3"), null, null, null, "report-service", "ACTIVE", null, "API_KEY", "kv://svc-key", service.base(),
                0, 0, "REST_AGENT", null, false);

        assertThatThrownBy(() -> runner.invoke(runs.invocation, spec, null)).isInstanceOfSatisfying(ApplicationFailure.class,
                f -> assertThat(f.getOriginalMessage()).contains("not granted"));
        assertThat(service.calls).isEmpty();
    }

    /** Submits a job and records it, as a first attempt would before its worker died. */
    private void invokeThenForget(String kind) {
        AgentSpec spec = spec(async(true), 30, null);
        runs.invocation = withSpec(spec);
        runs.invocation = new Invocation(RUN, "engineering", "RUNNING", "svc", 1, "h", "Svc", ContentHash.canonicalJson(spec),
                Map.of("input", kind), null, null, null, "report-service", "ACTIVE", null, "API_KEY", "kv://svc-key", service.base(),
                0, 0, "REST_AGENT", null, true);
        String id = "job-" + (service.kinds.size() + 1);
        service.kinds.put(id, kind);
        service.polls.put(id, new java.util.concurrent.atomic.AtomicInteger());
        service.states.put(id, "queued");
        runs.sends.put(RUN + ":0", "ACKED");
        runs.remote = new RemoteTask("rest-async", id, null, "WORKING", "queued", null);
    }

    private Invocation withSpec(AgentSpec spec) {
        return new Invocation(RUN, "engineering", "RUNNING", "svc", 1, "h", "Svc", ContentHash.canonicalJson(spec),
                Map.of("input", "x"), null, null, null, "report-service", "ACTIVE", null, "API_KEY", "kv://svc-key", service.base(),
                0, 0, "REST_AGENT", null, true);
    }
}
