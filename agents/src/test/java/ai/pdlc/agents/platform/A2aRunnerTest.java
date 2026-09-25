package ai.pdlc.agents.platform;

import ai.pdlc.adapters.a2a.A2aClient;
import ai.pdlc.adapters.a2a.TestA2aServer;
import ai.pdlc.adapters.mcp.McpAuth;
import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.agents.platform.RunStore.RemoteTask;
import ai.pdlc.agents.platform.RunStore.StoredMessage;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.workflow.AgentRunWorkflow.AgentRunOutcome;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link A2aRunner} against a real in-process A2A agent (1.0 and 0.3) and a stateful in-memory run store. */
class A2aRunnerTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-00000000a2a1");

    /** The run store's a2a surface, in memory; everything else is unused by the runner. */
    static final class FakeRuns extends RunStore {
        Invocation invocation;
        String status = "RUNNING";
        String output;
        String error;
        RemoteTask remote;
        final Map<String, String> sends = new ConcurrentHashMap<>();
        final List<StoredMessage> messages = new ArrayList<>();

        FakeRuns() {
            super(null);
        }

        @Override
        public Optional<Invocation> load(UUID runId) {
            Invocation i = invocation;
            return Optional.of(new Invocation(i.runId(), i.workspaceId(), status, i.agentId(), i.version(), i.contentHash(), i.name(),
                    i.specJson(), i.inputs(), i.model(), i.providerModel(), i.modelEnabled(), i.connectionId(), i.connectionStatus(),
                    i.connectionExpiresAt(), i.authType(), i.secretRef(), i.baseUrl(), i.attempts(), i.activeMs(), i.connectionKind(),
                    i.oauthClientId(), i.granted()));
        }

        @Override
        public Optional<RemoteTask> remoteTask(UUID runId) {
            return Optional.ofNullable(remote);
        }

        @Override
        public void saveRemoteTask(UUID runId, String dialect, String taskId, String contextId, String state, String statusText) {
            remote = new RemoteTask(dialect, remote != null && remote.taskId() != null ? remote.taskId() : taskId,
                    contextId != null ? contextId : remote == null ? null : remote.contextId(), state, statusText,
                    remote == null ? null : remote.cancel());
        }

        @Override
        public void setRemoteCancel(UUID runId, String cancel) {
            remote = new RemoteTask(remote.dialect(), remote.taskId(), remote.contextId(), remote.state(), remote.statusText(), cancel);
        }

        @Override
        public String sendState(UUID runId, String messageId) {
            return sends.get(messageId);
        }

        @Override
        public String intendSend(UUID runId, String messageId) {
            return sends.computeIfAbsent(messageId, k -> "INTENDED");
        }

        @Override
        public void setSendState(UUID runId, String messageId, String state) {
            sends.put(messageId, state);
        }

        @Override
        public List<StoredMessage> messages(UUID runId) {
            return List.copyOf(messages);
        }

        @Override
        public boolean appendMessage(UUID runId, int seq, String kind, String contentJson) {
            messages.add(new StoredMessage(seq, kind, contentJson));
            return true;
        }

        @Override
        public boolean pause(UUID runId, String s) {
            status = s;
            return true;
        }

        @Override
        public boolean complete(UUID runId, String outputText, String outputJson, Integer promptTokens, Integer completionTokens) {
            status = "SUCCEEDED";
            output = outputText;
            return true;
        }

        @Override
        public boolean fail(UUID runId, String e, String outputText) {
            status = "FAILED";
            error = e;
            return true;
        }

        @Override
        public void addActiveMs(UUID runId, long millis) {
        }
    }

    private TestA2aServer server;
    private final FakeRuns runs = new FakeRuns();
    private final EgressPolicy egress = new EgressPolicy(Set.of("localhost"));
    private final McpCredentials credentials = new McpCredentials(ref -> "kv://partner-key".equals(ref) ? "partner-secret" : null, egress);
    private final A2aRunner runner = new A2aRunner(runs, credentials, new A2aClient(egress::check), Clock.systemUTC(),
            Duration.ofMillis(10), Duration.ofMillis(40));

    @BeforeEach
    void start() throws Exception {
        server = new TestA2aServer();
        server.requiredBearer = "partner-secret";
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** A fresh remote agent (the test agent dedups on message id, and every scenario reuses run:0). */
    private void freshAgent(boolean streaming) throws Exception {
        server.close();
        server = new TestA2aServer();
        server.requiredBearer = "partner-secret";
        server.streaming = streaming;
        runs.remote = null;
        runs.sends.clear();
    }

    private static AgentSpec spec(String skill, int timeoutSeconds, Map<String, Object> outputSchema) {
        return new AgentSpec("Delegates", "a2a", "Summarise {{input}}", List.of(new AgentSpec.Variable("input", null, true)),
                null, new AgentSpec.Limits(null, timeoutSeconds), outputSchema, null, new AgentSpec.Remote("partner-agent", skill));
    }

    private AgentRunOutcome invoke(String skill) {
        return invoke(spec(skill, 30, null), true);
    }

    @Test
    void stopsFollowingOnceTheRunIsCancelled() {
        server.streaming = false;
        runs.status = "CANCELLED";

        assertThatThrownBy(() -> invoke(spec("hold", 30, null), true)).isInstanceOfSatisfying(ApplicationFailure.class,
                f -> assertThat(f.getOriginalMessage()).contains("stopped following remote task"));
        assertThat(server.calls).noneMatch(c -> c.startsWith("get "));
    }

    private AgentRunOutcome invoke(AgentSpec spec, boolean granted) {
        runs.invocation = new Invocation(RUN, "engineering", "RUNNING", "delegate", 1, ContentHash.ofAgent("Delegate", spec),
                "Delegate", ContentHash.canonicalJson(spec), Map.of("input", "Q3"), null, null, null, "partner-agent", "ACTIVE",
                null, "API_KEY", "kv://partner-key", server.origin().toString(), 0, 0, "A2A_AGENT", null, granted);
        return runner.invoke(runs.invocation, spec, "Summarise Q3");
    }

    @ParameterizedTest
    @EnumSource(A2aClient.Dialect.class)
    void delegatesTheRenderedPromptAndRecordsTheRemoteResult(A2aClient.Dialect dialect) {
        server.dialect = dialect;

        AgentRunOutcome outcome = invoke("echo");

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(runs.output).isEqualTo("echo: Summarise Q3");
        assertThat(runs.remote.taskId()).startsWith("task-");
        assertThat(runs.remote.state()).isEqualTo("COMPLETED");
        assertThat(runs.remote.dialect()).isEqualTo(dialect == A2aClient.Dialect.V1_0 ? "1.0" : "0.3");
        assertThat(runs.sends).containsEntry(RUN + ":0", "ACKED");
        assertThat(server.calls).containsExactly("stream " + RUN + ":0 Summarise Q3");
        assertThat(server.authorizations).allMatch("Bearer partner-secret"::equals);
    }

    @Test
    void pollsALongRemoteTaskWhenTheAgentDoesNotStream() {
        server.streaming = false;
        server.slowPolls = 3;

        assertThat(invoke("slow").status()).isEqualTo("SUCCEEDED");

        assertThat(runs.output).isEqualTo("slow result");
        assertThat(server.calls).filteredOn(c -> c.startsWith("get ")).hasSize(3);
    }

    @ParameterizedTest
    @EnumSource(A2aClient.Dialect.class)
    void inputRequiredPausesTheRunAndTheReplyContinuesTheSameTask(A2aClient.Dialect dialect) {
        server.dialect = dialect;
        server.streaming = false;

        AgentRunOutcome paused = invoke("ask");

        assertThat(paused.status()).isEqualTo("AWAITING_INPUT");
        assertThat(runs.status).isEqualTo("AWAITING_INPUT");
        assertThat(runs.remote.statusText()).isEqualTo("Which region should the report cover?");
        assertThat(runs.messages).singleElement().satisfies(m -> assertThat(m.kind()).isEqualTo("REMOTE_AGENT"));
        String taskId = runs.remote.taskId();

        runs.messages.add(new StoredMessage(2, "REMOTE_USER", "{\"text\":\"EMEA\",\"by\":\"op@acme\"}"));
        AgentRunOutcome done = invoke("ask");

        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(runs.output).isEqualTo("thanks: EMEA");
        assertThat(runs.remote.taskId()).isEqualTo(taskId);
        assertThat(server.calls).containsExactly("send " + RUN + ":0 Summarise Q3", "send " + RUN + ":2 EMEA");
    }

    @Test
    void aRemoteFailureFailsTheRunWithTheRemoteMessageAndAuthRequiredWaits() throws Exception {
        server.streaming = false;
        AgentRunOutcome failed = invoke("fail");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(runs.error).isEqualTo("the remote agent reported failed: the partner system is down");

        freshAgent(false);
        assertThat(invoke("login").status()).isEqualTo("AWAITING_AUTH");
    }

    @Test
    void aRetryAfterAnUnansweredSendReconcilesThroughTheTaskInsteadOfSendingAgain() {
        server.streaming = false;
        server.slowPolls = 1;
        server.dialect = A2aClient.Dialect.V1_0;
        // The previous attempt sent run:0 and learned the task id, then its worker died.
        A2aClient client = new A2aClient(egress::check);
        A2aClient.Task task = client.open(client.card(server.origin(), McpAuth.bearer("partner-secret"), Duration.ofSeconds(3), 64_000),
                McpAuth.bearer("partner-secret"), Duration.ofSeconds(3), 64_000, "slow").send(RUN + ":0", "Summarise Q3", null, null);
        runs.sends.put(RUN + ":0", "SENT");
        runs.remote = new RemoteTask("1.0", task.id(), task.contextId(), "WORKING", null, null);
        server.calls.clear();

        assertThat(invoke("slow").status()).isEqualTo("SUCCEEDED");

        assertThat(server.calls).noneMatch(c -> c.startsWith("send") || c.startsWith("stream"));
        assertThat(runs.remote.state()).isEqualTo("COMPLETED");
    }

    @Test
    void aSendWhoseOutcomeIsUnknownIsNeverResent() {
        runs.sends.put(RUN + ":0", "SENT");

        assertThatThrownBy(() -> invoke("echo")).isInstanceOfSatisfying(ApplicationFailure.class, f -> {
            assertThat(f.isNonRetryable()).isTrue();
            assertThat(f.getOriginalMessage()).contains("unknown; not resent");
        });
        assertThat(runs.error).startsWith("outcome unknown");
        assertThat(server.calls).isEmpty();
    }

    @Test
    void theCardAndTheConnectionAreRecheckedAtRunTime() {
        server.skills = List.of("echo");
        assertThatThrownBy(() -> invoke("ask")).isInstanceOfSatisfying(ApplicationFailure.class,
                f -> assertThat(f.getOriginalMessage()).isEqualTo("the remote agent's card no longer offers skill ask"));

        assertThatThrownBy(() -> invoke(spec("echo", 30, null), false)).isInstanceOfSatisfying(ApplicationFailure.class,
                f -> assertThat(f.getOriginalMessage()).contains("is not granted to workspace engineering"));
        assertThat(server.calls).isEmpty();
    }

    @Test
    void theRemoteOutputMustMatchTheDeclaredSchema() {
        server.streaming = false;
        assertThatThrownBy(() -> invoke(spec("echo", 30, Map.of("type", "object")), true))
                .isInstanceOfSatisfying(ApplicationFailure.class, f -> assertThat(f.getOriginalMessage()).contains("outputSchema"));
        assertThat(runs.status).isEqualTo("FAILED");
    }

    @Test
    void cancellationIsBestEffortAndItsAcknowledgementIsRecorded() throws Exception {
        server.streaming = false;
        invokeUntilWorking("hold");
        runner.cancelRemote(RUN.toString());
        assertThat(runs.remote.cancel()).isEqualTo("ACKNOWLEDGED");
        assertThat(server.state(runs.remote.taskId())).containsIgnoringCase("canceled");

        freshAgent(false);
        invokeUntilWorking("pinned");
        runner.cancelRemote(RUN.toString());
        assertThat(runs.remote.cancel()).isEqualTo("REFUSED");
    }

    @Test
    void runningOutOfTimeCancelsTheRemoteTaskAndFails() {
        server.streaming = false;

        assertThatThrownBy(() -> invoke(spec("hold", 1, null), true)).isInstanceOfSatisfying(ApplicationFailure.class,
                f -> assertThat(f.getOriginalMessage()).contains("did not finish within the agent's time; it was asked to cancel (acknowledged)"));
        assertThat(runs.remote.cancel()).isEqualTo("ACKNOWLEDGED");
    }

    /** Starts a task that stays working, then stops following it (as if the run were cancelled mid-flight). */
    private void invokeUntilWorking(String skill) {
        A2aClient client = new A2aClient(egress::check);
        A2aClient.Task task = client.open(client.card(server.origin(), McpAuth.bearer("partner-secret"), Duration.ofSeconds(3), 64_000),
                McpAuth.bearer("partner-secret"), Duration.ofSeconds(3), 64_000, skill).send(RUN + ":0", "x", null, null);
        runs.remote = new RemoteTask("1.0", task.id(), task.contextId(), "WORKING", null, null);
        runs.invocation = new Invocation(RUN, "engineering", "RUNNING", "delegate", 1, "h", "Delegate",
                ContentHash.canonicalJson(spec(skill, 30, null)), Map.of("input", "Q3"), null, null, null, "partner-agent", "ACTIVE",
                null, "API_KEY", "kv://partner-key", server.origin().toString(), 0, 0, "A2A_AGENT", null, true);
    }
}
