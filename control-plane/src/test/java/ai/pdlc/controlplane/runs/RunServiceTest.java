package ai.pdlc.controlplane.runs;

import ai.pdlc.controlplane.connections.InMemoryConnectionStore;
import ai.pdlc.controlplane.connections.ModelCatalog;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.AgentRegistryService;
import ai.pdlc.controlplane.platform.Capability;
import ai.pdlc.controlplane.platform.TestRegistries;
import ai.pdlc.controlplane.platform.InMemoryWorkspaceStore;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.ServiceUnavailableException;
import ai.pdlc.controlplane.web.dto.AgentDefinitionDto;
import ai.pdlc.controlplane.web.dto.AgentDraftRequest;
import ai.pdlc.controlplane.web.dto.RunDto;
import ai.pdlc.controlplane.web.dto.StartRunRequest;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
import ai.pdlc.core.platform.AgentSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Run start/pinning/idempotency/cancel rules over the real registry and catalog (in-memory stores, fake launcher). */
class RunServiceTest {

    static final Identity IT = new Identity("it@acme", "Admin");
    static final Identity LEAD = new Identity("lead@acme", "SquadLead");
    static final Identity AUTHOR = new Identity("author@acme", "FSDeveloper");
    static final Identity OPERATOR = new Identity("op@acme", "QA");
    static final Identity FIN = new Identity("fin@acme", "SquadLead");

    static final class FakeLauncher implements RunLauncher {
        final List<String> started = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        final List<String> signals = new ArrayList<>();
        RuntimeException failure;

        @Override
        public void start(String workflowId, String runId, int timeoutSeconds) {
            if (failure != null) {
                throw failure;
            }
            started.add(workflowId + "|" + runId + "|" + timeoutSeconds);
        }

        @Override
        public void cancel(String workflowId) {
            cancelled.add(workflowId);
        }

        @Override
        public void signalApproval(String workflowId, String approvalId) {
            if (failure != null) {
                throw failure;
            }
            signals.add("approval|" + workflowId + "|" + approvalId);
        }

        @Override
        public void signalEffect(String workflowId, String effectId) {
            signals.add("effect|" + workflowId + "|" + effectId);
        }

        @Override
        public void signalInput(String workflowId, String messageId) {
            signals.add("input|" + workflowId + "|" + messageId);
        }
    }

    private final InMemoryConnectionStore connections = InMemoryConnectionStore.withModels("gw", "sonnet");
    private final WorkspaceService workspaces = new WorkspaceService(new InMemoryWorkspaceStore());
    private final TestRegistries.Registries registries = TestRegistries.over(workspaces, connections);
    private final AgentRegistryService registry = registries.agents();
    private final InMemoryRunStore runs = new InMemoryRunStore();
    private final FakeLauncher launcher = new FakeLauncher();
    private final RunService service = new RunService(runs, launcher, registry, workspaces);

    static AgentSpec labelSpec(String label) {
        return new AgentSpec(null, "native", "Return the label " + label + " for {{input}}.",
                List.of(new AgentSpec.Variable("input", null, true)),
                new AgentSpec.ModelBinding("sonnet", List.of()), new AgentSpec.Limits(null, 45), null);
    }

    @BeforeEach
    void seed() {
        workspaces.create(new WorkspaceRequest("engineering", "Engineering", List.of(LEAD.user())), IT);
        workspaces.create(new WorkspaceRequest("finance", "Finance", List.of(FIN.user())), IT);
        workspaces.setMember("engineering", AUTHOR.user(), EnumSet.of(Capability.AUTHOR), LEAD);
        workspaces.setMember("engineering", OPERATOR.user(), EnumSet.of(Capability.OPERATOR), LEAD);
        AgentDefinitionDto created = registry.create("engineering",
                new AgentDraftRequest("labeler", "Labeler", labelSpec("ALPHA"), null), AUTHOR);
        registry.publish("engineering", "labeler", created.draftRevision(), LEAD);
    }

    private RunDto startRun(String key) {
        return service.start("engineering", "labeler", new StartRunRequest(Map.of("input", "order 42"), key), OPERATOR);
    }

    @Test
    void aRunPinsTheCurrentVersionAndModelAndLaunchesWithTheAgentsDeadline() {
        RunDto run = startRun(null);

        assertThat(run.status()).isEqualTo("QUEUED");
        assertThat(run.agentVersion()).isEqualTo(1);
        assertThat(run.contentHash()).startsWith("sha256:");
        assertThat(run.model()).isEqualTo("sonnet");
        assertThat(run.connectionId()).isEqualTo("gw");
        assertThat(run.inputs()).containsEntry("input", "order 42");
        assertThat(launcher.started).containsExactly("agent-run-" + run.id() + "|" + run.id() + "|45");
    }

    @Test
    void publishingV2DoesNotChangeARunAlreadyStarted() {
        RunDto v1Run = startRun(null);
        AgentDefinitionDto edited = registry.saveDraft("engineering", "labeler",
                new AgentDraftRequest(null, "Labeler", labelSpec("BETA"), 1), AUTHOR);
        registry.publish("engineering", "labeler", edited.draftRevision(), LEAD);

        RunDto v2Run = startRun(null);

        assertThat(service.get("engineering", v1Run.id(), OPERATOR).agentVersion()).isEqualTo(1);
        assertThat(v2Run.agentVersion()).isEqualTo(2);
        assertThat(v2Run.contentHash()).isNotEqualTo(v1Run.contentHash());
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameRunAndLaunchesOnce() {
        RunDto first = startRun("req-7");
        RunDto again = startRun("req-7");

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(launcher.started).hasSize(1);
    }

    @Test
    void anIdempotencyKeyCannotBeReusedForAnotherAgent() {
        startRun("req-8");
        AgentDefinitionDto other = registry.create("engineering",
                new AgentDraftRequest("other", "Other", labelSpec("X"), null), AUTHOR);
        registry.publish("engineering", "other", other.draftRevision(), LEAD);

        assertThatThrownBy(() -> service.start("engineering", "other",
                new StartRunRequest(Map.of("input", "x"), "req-8"), OPERATOR))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void invalidInputsAreRejectedBeforeAnythingIsRecorded() {
        assertThatThrownBy(() -> service.start("engineering", "labeler",
                new StartRunRequest(Map.of("secret", "x"), null), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("input \"input\" is required; input \"secret\" is not a declared variable");
        assertThat(runs.rows).isEmpty();
        assertThat(launcher.started).isEmpty();
    }

    @Test
    void onlyOperatorsStartOrCancelAndOtherWorkspacesSeeNothing() {
        assertThatThrownBy(() -> service.start("engineering", "labeler",
                new StartRunRequest(Map.of("input", "x"), null), AUTHOR)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.start("engineering", "labeler",
                new StartRunRequest(Map.of("input", "x"), "k"), AUTHOR)).isInstanceOf(ForbiddenException.class);
        RunDto run = startRun(null);

        assertThat(service.get("engineering", run.id(), AUTHOR).id()).isEqualTo(run.id());
        assertThatThrownBy(() -> service.get("engineering", run.id(), FIN)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get("finance", run.id(), FIN)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel("engineering", run.id(), AUTHOR)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aRevokedConnectionRefusesTheStart() {
        connections.revokeConnection("gw", "it@acme");

        assertThatThrownBy(() -> startRun(null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("connection gw revoked");
        assertThat(runs.rows).isEmpty();
    }

    @Test
    void cancelRequestsWorkflowCancellationAndTerminalRunsCannotBeCancelled() {
        RunDto run = startRun(null);

        service.cancel("engineering", run.id(), OPERATOR);
        assertThat(launcher.cancelled).containsExactly("agent-run-" + run.id());

        runs.withStatus(UUID.fromString(run.id()), "SUCCEEDED", null);
        assertThatThrownBy(() -> service.cancel("engineering", run.id(), OPERATOR)).isInstanceOf(ConflictException.class);
    }

    @Test
    void anUnreachableWorkflowServiceFailsTheRunVisibly() {
        launcher.failure = new IllegalStateException("UNAVAILABLE: io exception");

        assertThatThrownBy(() -> startRun(null)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(runs.rows.values()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo("FAILED");
            assertThat(r.error()).contains("Could not start the run workflow");
        });
    }

    @Test
    void anUnknownRunIdIsNotFound() {
        assertThatThrownBy(() -> service.get("engineering", "not-a-uuid", OPERATOR)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get("engineering", UUID.randomUUID().toString(), OPERATOR))
                .isInstanceOf(NotFoundException.class);
    }

    private RunDto startA2aRun() {
        registries.connections().createConnection(new ai.pdlc.controlplane.web.dto.ConnectionRequest("partner-agent", "A2A_AGENT",
                "API_KEY", "kv://partner-key", "https://api.example", null), IT);
        registries.connections().grant("partner-agent", "engineering", IT);
        AgentSpec spec = new AgentSpec("Delegates", "a2a", "Summarise {{input}}", List.of(new AgentSpec.Variable("input", null, true)),
                null, new AgentSpec.Limits(null, 120), null, null, new AgentSpec.Remote("partner-agent", "summarise"));
        AgentDefinitionDto created = registry.create("engineering", new AgentDraftRequest("delegate", "Delegate", spec, null), AUTHOR);
        registry.publish("engineering", "delegate", created.draftRevision(), LEAD);
        return service.start("engineering", "delegate", new StartRunRequest(Map.of("input", "Q3"), null), OPERATOR);
    }

    @Test
    void anA2aRunPinsItsConnectionAndHasNoModel() {
        RunDto run = startA2aRun();

        assertThat(run.model()).isNull();
        assertThat(run.providerModel()).isNull();
        assertThat(run.connectionId()).isEqualTo("partner-agent");
        assertThat(launcher.started).containsExactly("agent-run-" + run.id() + "|" + run.id() + "|120");

        registries.connections().revokeGrant("partner-agent", "engineering", IT);
        assertThatThrownBy(() -> service.start("engineering", "delegate", new StartRunRequest(Map.of("input", "Q3"), null), OPERATOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("connection partner-agent is not granted to workspace engineering");
    }

    @Test
    void anOperatorAnswersARemoteAgentOnceAndTheRunResumes() {
        RunDto run = startA2aRun();
        UUID id = UUID.fromString(run.id());
        assertThatThrownBy(() -> service.reply("engineering", run.id(), "EMEA", OPERATOR))
                .isInstanceOf(ConflictException.class).hasMessageContaining("not waiting for input");

        runs.withStatus(id, "AWAITING_INPUT", null);
        runs.remotes.put(id, new RunStore.RemoteRow("1.0", "task-1", "ctx-1", "INPUT_REQUIRED", "Which region?", null));
        assertThat(service.get("engineering", run.id(), OPERATOR).remote().question()).isEqualTo("Which region?");
        assertThatThrownBy(() -> service.reply("engineering", run.id(), "EMEA", AUTHOR)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.reply("engineering", run.id(), " ", OPERATOR)).isInstanceOf(IllegalArgumentException.class);

        RunDto resumed = service.reply("engineering", run.id(), "EMEA", OPERATOR);

        assertThat(resumed.status()).isEqualTo("QUEUED");
        assertThat(runs.replies).containsExactly("op@acme: EMEA");
        assertThat(launcher.signals).containsExactly("input|agent-run-" + run.id() + "|" + run.id() + ":1");
        assertThatThrownBy(() -> service.reply("engineering", run.id(), "APAC", OPERATOR)).isInstanceOf(ConflictException.class);
    }
}
