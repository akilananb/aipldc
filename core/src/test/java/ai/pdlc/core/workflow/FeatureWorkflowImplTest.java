package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.WorkItemRef;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TestWorkflowEnvironment} replay tests for {@link FeatureWorkflowImpl}: the full gate-1
 * sequence (draft → blocking comment → requestChanges → revise → approve×2 distinct identities →
 * completes) plus the three negative cases the plan calls out.
 */
class FeatureWorkflowImplTest {

    private TestWorkflowEnvironment testEnv;
    private FakeAgentActivities agentActivities;
    private FakeBoardSideEffects boardSideEffects;
    private FakeBuildActivities buildActivities;

    private FeatureWorkflow start(String boardId) {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(FeatureWorkflowImpl.class);
        agentActivities = new FakeAgentActivities();
        worker.registerActivitiesImplementations(agentActivities);

        Worker boardWorker = testEnv.newWorker(TaskQueues.BOARD);
        boardSideEffects = new FakeBoardSideEffects();
        boardWorker.registerActivitiesImplementations(boardSideEffects);

        Worker buildWorker = testEnv.newWorker(TaskQueues.BUILD);
        buildActivities = new FakeBuildActivities();
        buildWorker.registerActivitiesImplementations(buildActivities);

        testEnv.start();

        WorkflowClient client = testEnv.getWorkflowClient();
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId("feature-local-" + boardId)
                .build();
        FeatureWorkflow stub = client.newWorkflowStub(FeatureWorkflow.class, options);
        WorkflowClient.start(stub::run, new WorkItemRef("local", boardId));
        return stub;
    }

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    private void awaitState(FeatureWorkflow wf, java.util.function.Predicate<ReviewState> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test(wf.state())) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not met before deadline; last state=" + wf.state());
    }

    @Test
    void fullGate1SequenceDraftCommentRevisionTwoDistinctApprovalsCompletes() throws Exception {
        FeatureWorkflow wf = start("4412");

        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);
        assertThat(wf.state().version()).isEqualTo(1);
        assertThat(boardSideEffects.published).hasSize(1);
        assertThat(boardSideEffects.readyForStoryCalls).hasSize(1);

        wf.comment(new Comment("c1", "squad-lead@acme", "SquadLead", "story", "line:13",
                "make it 20/hour for admin", Comment.Intent.CHANGE, true, 1));
        awaitState(wf, s -> !s.openComments().isEmpty());

        wf.requestChanges("squad-lead@acme");
        awaitState(wf, s -> s.version() == 2 && s.openComments().isEmpty());
        assertThat(wf.state().openComments()).isEmpty(); // revision replies to every open comment
        assertThat(boardSideEffects.revisions).hasSize(1);
        assertThat(agentActivities.reviseCalls).hasSize(1);

        wf.approve(new Approval("po@acme", "PO", "story", 2, "hash-v2", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 2, "hash-v2", Instant.now()));

        // Gate 1 passing continues straight into plan -> build (omp over ACP, faked here) ->
        // review -> PR -> gate 2, reusing the same approve/comment signal surface.
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);
        assertThat(boardSideEffects.tasksPublished).hasSize(1);
        assertThat(boardSideEffects.inProgressCalls).hasSize(1);
        assertThat(buildActivities.taskIdsRun).containsExactly("T1");
        assertThat(boardSideEffects.prsOpened.get()).isEqualTo(1);
        assertThat(wf.state().version()).isEqualTo(1); // fresh version episode for gate 2

        wf.approve(new Approval("fsdev@acme", "FSDeveloper", "pr", 1, "hash-pr-v1", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "pr", 1, "hash-pr-v1", Instant.now()));

        // Gate 2 passing continues into the release agent's pack -> gate 3 (per-document signing,
        // same approve signal, stage "release-pack:<doc-id>") -> deploy -> one monitor pass.
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G3);
        assertThat(boardSideEffects.releasePacksPublished).hasSize(1);
        assertThat(wf.state().version()).isEqualTo(1); // fresh version episode for gate 3

        wf.approve(new Approval("po@acme", "PO", "release-pack:change-notes", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "release-pack:rollout-plan", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "release-pack:monitor-rules", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "release-pack:test-evidence", 1, "hash-doc", Instant.now()));

        WorkflowStub.fromTyped(wf).getResult(5, TimeUnit.SECONDS, Void.class);
        assertThat(wf.state().stage()).isEqualTo(CanonicalState.DONE);
        assertThat(boardSideEffects.approvedVersions).containsExactly(2, 1, 1); // gate 1 v2, gate 2 v1, gate 3 v1
        assertThat(boardSideEffects.deployedReleases).hasSize(1);
        assertThat(boardSideEffects.monitorEvaluations).hasSize(1);
    }

    @Test
    void staleVersionApprovalIsIgnored() throws Exception {
        FeatureWorkflow wf = start("4412-stale-version");
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);

        wf.approve(new Approval("po@acme", "PO", "story", 99, "hash-stale", Instant.now()));
        Thread.sleep(200); // give the signal a chance to be (mis)applied

        assertThat(wf.state().approvals()).isEmpty();
    }

    @Test
    void sameIdentityCannotSatisfyGateAsBothCheckers() throws Exception {
        FeatureWorkflow wf = start("4412-same-who");
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);

        wf.approve(new Approval("x@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("x@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        Thread.sleep(200);

        assertThat(wf.state().approvals()).hasSize(1).containsOnlyKeys("PO");
        // Gate never satisfies: the workflow must still be RUNNING (a satisfied gate would have
        // called board.transitionApproved and returned, completing the execution).
        assertThat(WorkflowStub.fromTyped(wf).describe().getStatus())
                .isEqualTo(io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
    }

    @Test
    void approvalWithOpenBlockingCommentDoesNotPassGate() throws Exception {
        FeatureWorkflow wf = start("4412-blocking");
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);

        wf.comment(new Comment("c1", "qa@acme", "QA", "story", "line:15",
                "also record filter hash", Comment.Intent.CHANGE, true, 1));
        awaitState(wf, s -> !s.openComments().isEmpty());

        wf.approve(new Approval("po@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        Thread.sleep(200);

        assertThat(wf.state().approvals()).hasSize(2); // both recorded...
        assertThat(wf.state().openComments()).hasSize(1); // ...but gate still blocked
        // Gate never satisfies: the workflow must still be RUNNING (a satisfied gate would have
        // called board.transitionApproved and returned, completing the execution).
        assertThat(WorkflowStub.fromTyped(wf).describe().getStatus())
                .isEqualTo(io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
    }

    @Test
    void fiveDayTimerWithOpenGrillQuestionEscalatesStale() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(FeatureWorkflowImpl.class);
        agentActivities = new FakeAgentActivities();
        agentActivities.startWithOpenQuestion = true;
        worker.registerActivitiesImplementations(agentActivities);

        Worker boardWorker = testEnv.newWorker(TaskQueues.BOARD);
        boardSideEffects = new FakeBoardSideEffects();
        boardWorker.registerActivitiesImplementations(boardSideEffects);
        testEnv.start();

        WorkflowClient client = testEnv.getWorkflowClient();
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId("feature-local-4412-stale")
                .build();
        FeatureWorkflow wf = client.newWorkflowStub(FeatureWorkflow.class, options);
        WorkflowClient.start(wf::run, new WorkItemRef("local", "4412-stale"));

        testEnv.sleep(Duration.ofDays(5).plusMinutes(1));
        awaitState(wf, s -> s.stage() == CanonicalState.STALE);
        assertThat(boardSideEffects.staleEscalations.get()).isEqualTo(1);

        wf.commentAdded(new BoardCommentEvent("board-c1", "PO", "current filtered view"));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);
    }

    @Test
    void gate2RejectsGate1RolesAndSameIdentityAsBothCheckers() throws Exception {
        FeatureWorkflow wf = start("4412-gate2-sod");
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);
        wf.approve(new Approval("po@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);

        // A gate-1 role has no standing at gate 2 - PO/SquadLead are not in gates.G2.roles.
        wf.approve(new Approval("po@acme", "PO", "pr", 1, "hash-pr-v1", Instant.now()));
        // Same physical identity cannot hold both gate-2 checker roles either.
        wf.approve(new Approval("y@acme", "FSDeveloper", "pr", 1, "hash-pr-v1", Instant.now()));
        wf.approve(new Approval("y@acme", "QA", "pr", 1, "hash-pr-v1", Instant.now()));
        Thread.sleep(200);

        assertThat(wf.state().approvals()).hasSize(1).containsOnlyKeys("FSDeveloper");
        assertThat(WorkflowStub.fromTyped(wf).describe().getStatus())
                .isEqualTo(io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

        wf.approve(new Approval("qa@acme", "QA", "pr", 1, "hash-pr-v1", Instant.now()));
        // Gate 2 satisfied continues the workflow onward (release pack -> gate 3, not this test's
        // concern) - proven by reaching AWAITING_G3, not by driving all the way to completion.
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G3);
    }

    @Test
    void buildLoopEscalationStillReachesReviewAndGate2() throws Exception {
        FeatureWorkflow wf = start("4412-build-escalation");
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);
        wf.approve(new Approval("po@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        // Playbook §4 "Stop conditions": budget exhausted -> WIP branch + escalation note is a
        // correct outcome, not a workflow failure - the build loop still hands off to review/PR/G2.
        buildActivities.returnRed = true;

        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);
        assertThat(buildActivities.taskIdsRun).containsExactly("T1");
        assertThat(boardSideEffects.prsOpened.get()).isEqualTo(1);
    }

    @Test
    void blockerFindingBlocksGate2EvenWithBothApprovals() throws Exception {
        FeatureWorkflow wf = start("4412-blocker-finding");
        agentActivities.returnBlockerFinding = true;
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);
        wf.approve(new Approval("po@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);

        // The review agent's blocker finding seeded a blocking comment; both gate-2 approvals
        // still cannot pass the gate while it's open (ReviewHandoff#hasBlockers() enforced).
        assertThat(wf.state().openComments()).hasSize(1);
        wf.approve(new Approval("fsdev@acme", "FSDeveloper", "pr", 1, "hash-pr-v1", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "pr", 1, "hash-pr-v1", Instant.now()));
        Thread.sleep(200);

        assertThat(wf.state().approvals()).hasSize(2);
        assertThat(wf.state().openComments()).hasSize(1);
        assertThat(WorkflowStub.fromTyped(wf).describe().getStatus())
                .isEqualTo(io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
    }

    @Test
    void gate3RejectsWrongCheckerRoleAndOnlyPassesWhenEveryDocumentIsSignedByItsOwnRole() throws Exception {
        FeatureWorkflow wf = start("4412-gate3-sod");
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);
        wf.approve(new Approval("po@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);
        wf.approve(new Approval("fsdev@acme", "FSDeveloper", "pr", 1, "hash-pr-v1", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "pr", 1, "hash-pr-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G3);

        // change-notes' checker is PO, not SquadLead - rejected even though SquadLead is a valid
        // gate-3 role in general (checker roles are per-document, not a shared gate role list).
        wf.approve(new Approval("lead@acme", "SquadLead", "release-pack:change-notes", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("po@acme", "PO", "release-pack:change-notes", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "release-pack:rollout-plan", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "release-pack:monitor-rules", 1, "hash-doc", Instant.now()));
        Thread.sleep(200);

        // 3 of 4 documents signed (test-evidence still missing) - gate 3 not yet satisfied.
        assertThat(wf.state().approvals()).hasSize(3)
                .containsOnlyKeys("change-notes", "rollout-plan", "monitor-rules");
        assertThat(WorkflowStub.fromTyped(wf).describe().getStatus())
                .isEqualTo(io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

        wf.approve(new Approval("qa@acme", "QA", "release-pack:test-evidence", 1, "hash-doc", Instant.now()));
        WorkflowStub.fromTyped(wf).getResult(5, TimeUnit.SECONDS, Void.class);
        assertThat(wf.state().stage()).isEqualTo(CanonicalState.DONE);
    }

    @Test
    void gate3RequestChangesClearsEverySignature() throws Exception {
        FeatureWorkflow wf = start("4412-gate3-request-changes");
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);
        wf.approve(new Approval("po@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);
        wf.approve(new Approval("fsdev@acme", "FSDeveloper", "pr", 1, "hash-pr-v1", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "pr", 1, "hash-pr-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G3);

        wf.approve(new Approval("po@acme", "PO", "release-pack:change-notes", 1, "hash-doc", Instant.now()));
        awaitState(wf, s -> !s.approvals().isEmpty());

        wf.requestChanges("squad-lead@acme");
        awaitState(wf, s -> s.version() == 2);

        // playbook §7 "Rules": a changes-requested bumps pack_version and clears every signature.
        assertThat(wf.state().approvals()).isEmpty();
        assertThat(WorkflowStub.fromTyped(wf).describe().getStatus())
                .isEqualTo(io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
    }

    @Test
    void monitorTripAfterDeployFilesACard() throws Exception {
        FeatureWorkflow wf = start("4412-monitor-trip");
        agentActivities.returnMonitorTrip = true;
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);
        wf.approve(new Approval("po@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);
        wf.approve(new Approval("fsdev@acme", "FSDeveloper", "pr", 1, "hash-pr-v1", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "pr", 1, "hash-pr-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G3);
        wf.approve(new Approval("po@acme", "PO", "release-pack:change-notes", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "release-pack:rollout-plan", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "release-pack:monitor-rules", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "release-pack:test-evidence", 1, "hash-doc", Instant.now()));

        WorkflowStub.fromTyped(wf).getResult(5, TimeUnit.SECONDS, Void.class);

        assertThat(boardSideEffects.deployedReleases).hasSize(1);
        assertThat(boardSideEffects.monitorEvaluations).hasSize(1);
        assertThat(boardSideEffects.monitorEvaluations.get(0).anyTripped()).isTrue();
        assertThat(boardSideEffects.monitorEvaluations.get(0).trips()).hasSize(1);
    }

    @Test
    void qualityFailureAutoRevisesThenGateOpens() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(FeatureWorkflowImpl.class);
        agentActivities = new FakeAgentActivities();
        agentActivities.failStoryQualityOnce = true;
        worker.registerActivitiesImplementations(agentActivities);

        Worker boardWorker = testEnv.newWorker(TaskQueues.BOARD);
        boardSideEffects = new FakeBoardSideEffects();
        boardWorker.registerActivitiesImplementations(boardSideEffects);

        Worker buildWorker = testEnv.newWorker(TaskQueues.BUILD);
        buildActivities = new FakeBuildActivities();
        buildWorker.registerActivitiesImplementations(buildActivities);

        testEnv.start();

        WorkflowClient client = testEnv.getWorkflowClient();
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId("feature-local-4412-quality-fail")
                .build();
        FeatureWorkflow wf = client.newWorkflowStub(FeatureWorkflow.class, options);
        WorkflowClient.start(wf::run, new WorkItemRef("local", "4412-quality-fail"));

        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1 && s.version() == 2);
        assertThat(agentActivities.reviseCalls).hasSize(1);
        assertThat(boardSideEffects.qualityReportsSaved).hasSize(2);
        assertThat(boardSideEffects.qualityReportsSaved.get(0)).endsWith(":1:failed");
        assertThat(boardSideEffects.qualityReportsSaved.get(1)).endsWith(":2:passed");

        wf.approve(new Approval("po@acme", "PO", "story", 2, "hash-v2", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 2, "hash-v2", Instant.now()));

        // Gate 1 still opens normally once the quality gate passed and both approvals land.
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);
    }

    @Test
    void secondStoryQueuedUntilFirstDone() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(FeatureWorkflowImpl.class);
        agentActivities = new FakeAgentActivities();
        agentActivities.storyCount = 2;
        worker.registerActivitiesImplementations(agentActivities);

        Worker boardWorker = testEnv.newWorker(TaskQueues.BOARD);
        boardSideEffects = new FakeBoardSideEffects();
        boardWorker.registerActivitiesImplementations(boardSideEffects);

        Worker buildWorker = testEnv.newWorker(TaskQueues.BUILD);
        buildActivities = new FakeBuildActivities();
        buildWorker.registerActivitiesImplementations(buildActivities);

        testEnv.start();

        WorkflowClient client = testEnv.getWorkflowClient();
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId("feature-local-4412-multi-story")
                .build();
        FeatureWorkflow wf = client.newWorkflowStub(FeatureWorkflow.class, options);
        WorkflowClient.start(wf::run, new WorkItemRef("local", "4412-multi-story"));

        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G1);
        assertThat(boardSideEffects.published).hasSize(2);
        assertThat(boardSideEffects.queuedFlags).containsExactly(false, true);
        assertThat(boardSideEffects.activatedStories).isEmpty();
        assertThat(wf.state().activeStoryBoardId()).isEqualTo("story-0");

        // Drive story 1 (the active one) fully through G1 -> plan -> build -> G2 -> release -> G3 -> deploy.
        wf.approve(new Approval("po@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);
        wf.approve(new Approval("fsdev@acme", "FSDeveloper", "pr", 1, "hash-pr-v1", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "pr", 1, "hash-pr-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G3);
        wf.approve(new Approval("po@acme", "PO", "release-pack:change-notes", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "release-pack:rollout-plan", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "release-pack:monitor-rules", 1, "hash-doc", Instant.now()));
        wf.approve(new Approval("qa@acme", "QA", "release-pack:test-evidence", 1, "hash-doc", Instant.now()));

        // Story 2 activates and becomes the pipeline's active story only after story 1 finishes.
        awaitState(wf, s -> "story-1".equals(s.activeStoryBoardId()));
        assertThat(boardSideEffects.activatedStories).extracting(WorkItemRef::boardId).containsExactly("story-1");
        assertThat(wf.state().stage()).isEqualTo(CanonicalState.AWAITING_G1);

        // Drive story 2 through gate 1 to prove the workflow reaches it and doesn't hang.
        wf.approve(new Approval("po@acme", "PO", "story", 1, "hash-v1", Instant.now()));
        wf.approve(new Approval("lead@acme", "SquadLead", "story", 1, "hash-v1", Instant.now()));
        awaitState(wf, s -> s.stage() == CanonicalState.AWAITING_G2);
    }
}
