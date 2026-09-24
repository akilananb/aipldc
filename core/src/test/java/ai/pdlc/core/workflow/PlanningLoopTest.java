package ai.pdlc.core.workflow;

import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.AgentMentionRequest;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillRound;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.PlanConsultation;
import ai.pdlc.core.domain.PlanDecision;
import ai.pdlc.core.domain.PlanResult;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PlanningLoop} has no {@code Workflow.*} dependency of its own (only its caller,
 * {@link FeatureWorkflowImpl}, executes inside a workflow) — most cases here are direct
 * (non-Temporal) unit tests with scripted fakes, matching the package's existing hand-written
 * {@code FakeAgentActivities}/{@code FakeBuildActivities} pattern without paying for a
 * {@link TestWorkflowEnvironment} on every case. One dedicated test exercises the loop inside a
 * real minimal workflow and replays its captured history to prove determinism.
 */
class PlanningLoopTest {

    private static final WorkItemRef STORY = new WorkItemRef("local", "4500");

    private static final List<RepoConfig> REPOS = List.of(
            new RepoConfig("main", "local-git", "url", "main", "openspec", List.of(), true));

    private static PoHandoff po(String... scenarios) {
        Handoff envelope = new Handoff("po-agent", "plan-agent", STORY.boardId(),
                CanonicalState.APPROVED, List.of(), 0.9, List.of(), List.of());
        return new PoHandoff(envelope, STORY.boardId(), "openspec/changes/x", List.of(scenarios),
                Map.of(), List.of("area"), Map.of(), List.of(), Map.of());
    }

    private static PlanDecision.PlannedTask task(String id, String scenario, List<String> touches, String testPath) {
        return new PlanDecision.PlannedTask(id, "Title for " + scenario, "Implements " + scenario + ".",
                "area", "main", scenario, touches, testPath, List.of());
    }

    private static PlanConsultation.Report report(String sha, String findings, Map<String, Boolean> files) {
        List<PlanConsultation.FileEvidence> evidence = new ArrayList<>();
        files.forEach((path, exists) -> evidence.add(new PlanConsultation.FileEvidence(path, exists)));
        return new PlanConsultation.Report(sha, findings, evidence);
    }

    private record NextStepCall(List<PlanConsultation.Exchange> history, List<String> feedback, boolean canConsult) {
    }

    /** Scripted reasoning fake: returns each queued decision in order (as a function of the call's
     * own history/feedback/canConsult), recording every call. Throws (fails the test) if the
     * script is exhausted - too few scripted decisions is a test bug, not silently tolerated. */
    private static final class ScriptedReasoning implements AgentActivities {
        private final Deque<Function<NextStepCall, PlanDecision>> script;
        final List<NextStepCall> calls = new ArrayList<>();

        ScriptedReasoning(List<Function<NextStepCall, PlanDecision>> script) {
            this.script = new ArrayDeque<>(script);
        }

        @Override
        public PlanDecision planNextStep(WorkItemRef story, PoHandoff po, String storyMarkdown,
                                          List<RepoConfig> repos, List<PlanConsultation.Exchange> history, List<String> feedback, boolean canConsult) {
            NextStepCall call = new NextStepCall(history, feedback, canConsult);
            calls.add(call);
            if (script.isEmpty()) {
                throw new AssertionError("ScriptedReasoning script exhausted after " + calls.size() + " calls");
            }
            return script.poll().apply(call);
        }

        @Override
        public GrillHandoff grillEvaluate(WorkItemRef item, GrillHandoff previous, List<BoardCommentEvent> newComments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GrillRound grillNextRound(WorkItemRef item, GrillHandoff previous) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PoDraftResult poDraft(WorkItemRef item, GrillHandoff grill, boolean allowFollowUps) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoryDraft poRevise(WorkItemRef item, PoHandoff previous, List<Comment> openComments, GrillHandoff grill) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QualityReport evaluateQuality(WorkItemRef item, String subjectKind, String contentMd) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReviewHandoff reviewStory(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReleaseHandoff draftReleasePack(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results, ReviewHandoff review, List<Comment> feedback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MonitorHandoff evaluateMonitorRules(WorkItemRef story, List<MonitorRule> rules) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String mentionAnalyze(AgentMentionRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    /** Scripted consultant fake: returns each queued report in order (or lets a scripted
     * RuntimeException propagate) for {@code consultPlan} calls. */
    private static final class ScriptedConsultant implements BuildActivities {
        private final Deque<Function<PlanConsultation, PlanConsultation.Report>> script;
        final List<PlanConsultation> calls = new ArrayList<>();

        ScriptedConsultant(List<Function<PlanConsultation, PlanConsultation.Report>> script) {
            this.script = new ArrayDeque<>(script);
        }

        @Override
        public PlanConsultation.Report consultPlan(WorkItemRef story, PoHandoff po, PlanConsultation consultation) {
            calls.add(consultation);
            if (script.isEmpty()) {
                throw new AssertionError("ScriptedConsultant script exhausted after " + calls.size() + " calls");
            }
            return script.poll().apply(consultation);
        }

        @Override
        public BuildResult runTask(WorkItemRef story, Task task, String branch, String baseBranch, List<String> feedback) {
            throw new UnsupportedOperationException();
        }
    }

    /** Immediate-propagation {@link PlanningLoop.StepRunner}: calls {@code call.get()} directly,
     * matching today's pre-retry-handling behavior — {@link FeatureWorkflowImpl#reasoningStep} is
     * the block-and-retry production implementation, exercised separately in
     * {@code FeatureWorkflowImplTest}. */
    private static <T> PlanningLoop.StepRunner<T> passthrough() {
        return (step, call) -> call.get();
    }

    @Test
    void twoConsultationsThenFinalizeReflectsTheSecondReportsEvidence() {
        PoHandoff po = po("scenario-a");
        ScriptedConsultant consultant = new ScriptedConsultant(List.of(
                call -> report("a".repeat(40), "src/auth.js has the authorization check.",
                        Map.of("src/auth.js", true)),
                call -> report("a".repeat(40), "src/audit.js has the shared audit helper.",
                        Map.of("src/audit.js", true, "test/audit.test.js", false))));
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need authorization locations",
                        "main", List.of("Where is the authorization check implemented?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need the shared audit helper",
                        "main", List.of("Where is the shared audit helper?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.FINALIZE, "evidence sufficient", null, List.of(), List.of(),
                        List.of(task("T1", "scenario-a", List.of("src/audit.js"), "test/audit.test.js")))));

        PlanResult result = PlanningLoop.run(STORY, po, "# story", List.of(), REPOS, reasoning, consultant, passthrough(), passthrough());

        assertThat(consultant.calls).hasSize(2);
        assertThat(reasoning.calls).hasSize(3);
        assertThat(result.plan().tasks()).hasSize(1);
        assertThat(result.plan().tasks().get(0).touches()).containsExactly("src/audit.js");
        assertThat(result.checksByTaskId().get("T1").reportMd()).contains("test/audit.test.js (new file)");
        // The second consultation's history carries the first exchange.
        assertThat(reasoning.calls.get(1).history()).hasSize(1);
        assertThat(reasoning.calls.get(2).history()).hasSize(2);
    }

    @Test
    void initialFeedbackReachesTheFirstDecision() {
        PoHandoff po = po("scenario-a");
        List<String> initialFeedback = List.of("split T1");
        ScriptedConsultant consultant = new ScriptedConsultant(List.of(
                call -> report("a".repeat(40), "src/a.js implements scenario-a.", Map.of("src/a.js", true, "test/a.test.js", true))));
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need evidence", "main", List.of("where?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.FINALIZE, "grounded", null, List.of(), List.of(),
                        List.of(task("T1", "scenario-a", List.of("src/a.js"), "test/a.test.js")))));

        PlanResult result = PlanningLoop.run(STORY, po, "# story", initialFeedback, REPOS, reasoning, consultant, passthrough(), passthrough());

        assertThat(reasoning.calls.get(0).feedback()).isEqualTo(initialFeedback);
        assertThat(result.plan().tasks()).hasSize(1);
    }

    @Test
    void budgetExhaustionBlocksAfterThreeConsultationsAndSixDecisions() {
        Function<NextStepCall, PlanDecision> alwaysConsult = call -> new PlanDecision(
                PlanDecision.Action.CONSULT, "still need evidence", "main", List.of("more?"), List.of(), List.of());
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                alwaysConsult, alwaysConsult, alwaysConsult, alwaysConsult, alwaysConsult, alwaysConsult));
        ScriptedConsultant consultant = new ScriptedConsultant(List.of(
                call -> report("a".repeat(40), "finding 1", Map.of("a.js", true)),
                call -> report("a".repeat(40), "finding 2", Map.of("b.js", true)),
                call -> report("a".repeat(40), "finding 3", Map.of("c.js", true))));

        assertThatThrownBy(() -> PlanningLoop.run(STORY, po("s"), "# story", List.of(), REPOS, reasoning, consultant, passthrough(), passthrough()))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(e -> assertThat(((ApplicationFailure) e).getType()).isEqualTo("planning-blocked"));

        assertThat(reasoning.calls).hasSize(6);
        assertThat(consultant.calls).hasSize(3); // budget exhausted after the 3rd successful consultation
        assertThat(reasoning.calls.get(0).canConsult()).isTrue();
        assertThat(reasoning.calls.get(1).canConsult()).isTrue();
        assertThat(reasoning.calls.get(2).canConsult()).isTrue();
        assertThat(reasoning.calls.get(3).canConsult()).isFalse();
        assertThat(reasoning.calls.get(4).canConsult()).isFalse();
        assertThat(reasoning.calls.get(5).canConsult()).isFalse();
    }

    @Test
    void invalidFinalPlanIsCorrectedWithoutAnAdditionalConsultationCall() {
        PoHandoff po = po("scenario-a");
        ScriptedConsultant consultant = new ScriptedConsultant(List.of(
                call -> report("a".repeat(40), "src/a.js implements scenario-a.", Map.of("src/a.js", true, "test/a.test.js", false))));
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need evidence", "main", List.of("where?"), List.of(), List.of()),
                // Invalid: touches a path never checked in the consultation catalog.
                call -> new PlanDecision(PlanDecision.Action.FINALIZE, "first attempt", null, List.of(), List.of(),
                        List.of(task("T1", "scenario-a", List.of("src/unseen.js"), "test/a.test.js"))),
                call -> new PlanDecision(PlanDecision.Action.FINALIZE, "corrected", null, List.of(), List.of(),
                        List.of(task("T1", "scenario-a", List.of("src/a.js"), "test/a.test.js")))));

        PlanResult result = PlanningLoop.run(STORY, po, "# story", List.of(), REPOS, reasoning, consultant, passthrough(), passthrough());

        assertThat(consultant.calls).hasSize(1); // no extra consultation - just a corrected FINALIZE
        assertThat(reasoning.calls).hasSize(3);
        assertThat(reasoning.calls.get(2).feedback()).isNotEmpty();
        assertThat(result.plan().tasks().get(0).touches()).containsExactly("src/a.js");
    }

    @Test
    void blockedActionStopsImmediatelyWithTheAgentsReason() {
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                call -> new PlanDecision(PlanDecision.Action.BLOCKED, "requirements contradict the existing repo layout",
                        null, List.of(), List.of(), List.of())));
        ScriptedConsultant consultant = new ScriptedConsultant(List.of());

        assertThatThrownBy(() -> PlanningLoop.run(STORY, po("s"), "# story", List.of(), REPOS, reasoning, consultant, passthrough(), passthrough()))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("requirements contradict the existing repo layout");
        assertThat(consultant.calls).isEmpty();
    }

    @Test
    void firstDecisionMustBeConsultOrBlockedAnythingElseIsAnInvalidTransition() {
        PoHandoff po = po("scenario-a");
        ScriptedConsultant consultant = new ScriptedConsultant(List.of(
                call -> report("a".repeat(40), "src/a.js implements scenario-a.", Map.of("src/a.js", true, "test/a.test.js", true))));
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                // Invalid: FINALIZE with zero prior evidence.
                call -> new PlanDecision(PlanDecision.Action.FINALIZE, "premature", null, List.of(), List.of(),
                        List.of(task("T1", "scenario-a", List.of("src/a.js"), "test/a.test.js"))),
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need evidence after all", "main", List.of("where?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.FINALIZE, "now grounded", null, List.of(), List.of(),
                        List.of(task("T1", "scenario-a", List.of("src/a.js"), "test/a.test.js")))));

        PlanResult result = PlanningLoop.run(STORY, po, "# story", List.of(), REPOS, reasoning, consultant, passthrough(), passthrough());

        assertThat(reasoning.calls).hasSize(3);
        assertThat(reasoning.calls.get(1).feedback()).anyMatch(line -> line.contains("must be CONSULT"));
        assertThat(result.plan().tasks()).hasSize(1);
    }

    @Test
    void aConsultantFailurePropagatesRatherThanProducingAnEmptySuccessfulPlan() {
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need evidence", "main", List.of("where?"), List.of(), List.of())));
        ScriptedConsultant consultant = new ScriptedConsultant(List.of(
                call -> { throw new RuntimeException("consultant activity failed"); }));

        assertThatThrownBy(() -> PlanningLoop.run(STORY, po("s"), "# story", List.of(), REPOS, reasoning, consultant, passthrough(), passthrough()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("consultant activity failed");
    }

    @Test
    void aReasoningFailurePropagatesRatherThanProducingAnEmptySuccessfulPlan() {
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                call -> { throw new RuntimeException("reasoning activity failed"); }));
        ScriptedConsultant consultant = new ScriptedConsultant(List.of());

        assertThatThrownBy(() -> PlanningLoop.run(STORY, po("s"), "# story", List.of(), REPOS, reasoning, consultant, passthrough(), passthrough()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("reasoning activity failed");
    }

    @Test
    void consultWithNullOrUnknownRepoProducesFeedbackAndNeverInvokesConsultant() {
        PoHandoff po = po("scenario-a");
        ScriptedConsultant consultant = new ScriptedConsultant(List.of(
                call -> report("a".repeat(40), "src/a.js implements scenario-a.", Map.of("src/a.js", true, "test/a.test.js", true))));
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need evidence", null,
                        List.of("where?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need evidence", "unknown-repo",
                        List.of("where?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need evidence", "main",
                        List.of("where?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.FINALIZE, "grounded", null, List.of(), List.of(),
                        List.of(task("T1", "scenario-a", List.of("src/a.js"), "test/a.test.js")))));

        PlanResult result = PlanningLoop.run(STORY, po, "# story", List.of(), REPOS, reasoning, consultant, passthrough(), passthrough());

        // Both invalid CONSULTs produced feedback and looped without calling the consultant; only
        // the valid "main" round reached it.
        assertThat(consultant.calls).hasSize(1);
        assertThat(consultant.calls.get(0).repoId()).isEqualTo("main");
        assertThat(reasoning.calls).hasSize(4);
        assertThat(reasoning.calls.get(1).feedback())
                .containsExactly("CONSULT must name one of the project repos: [main]");
        assertThat(reasoning.calls.get(2).feedback())
                .containsExactly("CONSULT must name one of the project repos: [main]");
        assertThat(result.plan().tasks()).hasSize(1);
    }

    @Test
    void consultationsOnDifferentReposPinIndependentBaseCommits() {
        List<RepoConfig> repos = List.of(
                new RepoConfig("main", "local-git", "url", "main", "openspec", List.of(), true),
                new RepoConfig("web", "local-git", "url", "main", "openspec", List.of(), false));
        PoHandoff po = po("scenario-a");

        ScriptedConsultant consultant = new ScriptedConsultant(List.of(
                call -> report("a".repeat(40), "main finding", Map.of("src/a.js", true, "test/a.test.js", true)),
                call -> report("b".repeat(40), "web finding", Map.of("src/a.js", true, "test/a.test.js", true))));
        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "inspect main", "main",
                        List.of("where?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "inspect web", "web",
                        List.of("where?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.FINALIZE, "grounded", null, List.of(), List.of(),
                        List.of(task("T1", "scenario-a", List.of("src/a.js"), "test/a.test.js")))));

        PlanResult result = PlanningLoop.run(STORY, po, "# story", List.of(), repos, reasoning, consultant, passthrough(), passthrough());

        // web pins a different snapshot than main's — consulting a second repo does NOT require
        // matching the first repo's pinned commit.
        assertThat(consultant.calls).hasSize(2);
        assertThat(consultant.calls.get(0).repoId()).isEqualTo("main");
        assertThat(consultant.calls.get(1).repoId()).isEqualTo("web");
        assertThat(result.plan().tasks()).hasSize(1);
        // Both repos' own pinned snapshots are recorded - web's b*40 was accepted despite main's
        // a*40, proving per-repo pinning rather than a single transcript-wide commit.
        assertThat(result.plan().envelope().inputsRead())
                .contains("repo:" + "a".repeat(40), "repo:" + "b".repeat(40));

        // A second consult of the SAME repo must agree with that repo's own earlier pin: main was
        // pinned to a*40, so a main report at b*40 is rejected.
        ScriptedConsultant disagreeing = new ScriptedConsultant(List.of(
                call -> report("a".repeat(40), "main finding", Map.of("src/a.js", true)),
                call -> report("b".repeat(40), "main disagreement", Map.of("src/a.js", true))));
        ScriptedReasoning disagreeingReasoning = new ScriptedReasoning(List.of(
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "inspect main", "main",
                        List.of("where?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "re-inspect main", "main",
                        List.of("where?"), List.of(), List.of())));

        assertThatThrownBy(() -> PlanningLoop.run(STORY, po, "# story", List.of(), repos,
                disagreeingReasoning, disagreeing, passthrough(), passthrough()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the pinned commit");
    }

    // -- one Temporal-backed test: proves replaying the captured history is side-effect free -----

    @WorkflowInterface
    public interface TestPlanningWorkflow {
        @WorkflowMethod
        PlanResult run(WorkItemRef story, PoHandoff po, String storyMarkdown);
    }

    public static class TestPlanningWorkflowImpl implements TestPlanningWorkflow {
        private static final ActivityOptions REASONING_OPTIONS = ActivityOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setStartToCloseTimeout(Duration.ofMinutes(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build();
        private static final ActivityOptions CONSULT_OPTIONS = ActivityOptions.newBuilder()
                .setTaskQueue(TaskQueues.BUILD)
                .setStartToCloseTimeout(Duration.ofMinutes(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build();

        private final AgentActivities reasoning = Workflow.newActivityStub(AgentActivities.class, REASONING_OPTIONS);
        private final BuildActivities consultant = Workflow.newActivityStub(BuildActivities.class, CONSULT_OPTIONS);

        @Override
        public PlanResult run(WorkItemRef story, PoHandoff po, String storyMarkdown) {
            return PlanningLoop.run(story, po, storyMarkdown, List.of(), REPOS, reasoning, consultant, passthrough(), passthrough());
        }
    }

    private TestWorkflowEnvironment testEnv;

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    void replayingTheCapturedHistoryIsDeterministicAndNeverReInvokesTheActivities() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();

        Worker reasoningWorker = testEnv.newWorker(TaskQueues.REASONING);
        reasoningWorker.registerWorkflowImplementationTypes(TestPlanningWorkflowImpl.class);

        ScriptedReasoning reasoning = new ScriptedReasoning(List.of(
                call -> new PlanDecision(PlanDecision.Action.CONSULT, "need evidence", "main", List.of("where?"), List.of(), List.of()),
                call -> new PlanDecision(PlanDecision.Action.FINALIZE, "grounded", null, List.of(), List.of(),
                        List.of(task("T1", "scenario-a", List.of("src/a.js"), "test/a.test.js")))));
        ScriptedConsultant consultant = new ScriptedConsultant(List.of(
                call -> report("a".repeat(40), "src/a.js implements scenario-a.", Map.of("src/a.js", true, "test/a.test.js", true))));

        Worker reasoningActivityWorker = testEnv.newWorker(TaskQueues.REASONING);
        reasoningActivityWorker.registerActivitiesImplementations(reasoning);
        Worker consultWorker = testEnv.newWorker(TaskQueues.BUILD);
        consultWorker.registerActivitiesImplementations(consultant);

        testEnv.start();

        WorkflowClient client = testEnv.getWorkflowClient();
        String workflowId = "test-planning-loop-" + UUID.randomUUID();
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId(workflowId)
                .build();
        TestPlanningWorkflow stub = client.newWorkflowStub(TestPlanningWorkflow.class, options);

        PoHandoff po = po("scenario-a");
        WorkflowClient.start(stub::run, STORY, po, "# story");
        WorkflowStub untyped = WorkflowStub.fromTyped(stub);
        PlanResult result = untyped.getResult(10, TimeUnit.SECONDS, PlanResult.class);

        assertThat(result.plan().tasks()).hasSize(1);
        assertThat(reasoning.calls).hasSize(2);
        assertThat(consultant.calls).hasSize(1);

        WorkflowExecutionHistory history = client.fetchHistory(workflowId);
        WorkflowReplayer.replayWorkflowExecution(history, TestPlanningWorkflowImpl.class);

        // Replay uses the recorded activity results from history - it never re-invokes the
        // scripted activities, proving no consultation side effect repeats during replay.
        assertThat(reasoning.calls).hasSize(2);
        assertThat(consultant.calls).hasSize(1);
    }
}
