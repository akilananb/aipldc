package ai.pdlc.core.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link AgentRunWorkflowImpl} against a fake {@link AgentRunActivities}: retry, rejection and cancellation semantics. */
class AgentRunWorkflowImplTest {

    private TestWorkflowEnvironment testEnv;
    private FakeAgentRunActivities activities;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(AgentRunWorkflowImpl.class);
        activities = new FakeAgentRunActivities();
        worker.registerActivitiesImplementations(activities);
        testEnv.start();
        client = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private AgentRunWorkflow stub(String id) {
        return client.newWorkflowStub(AgentRunWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TaskQueues.REASONING).setWorkflowId("agent-run-" + id).build());
    }

    @Test
    void aSuccessfulInvocationReturnsSucceeded() {
        AgentRunWorkflow.AgentRunOutcome outcome = stub("r1").run(new AgentRunWorkflow.AgentRunInput("r1", 60));

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(activities.events).containsExactly("succeeded:r1");
    }

    @Test
    void aTransientProviderErrorIsRetriedOnce() {
        activities.mode = FakeAgentRunActivities.Mode.FAIL_ONCE_THEN_SUCCEED;

        AgentRunWorkflow.AgentRunOutcome outcome = stub("r2").run(new AgentRunWorkflow.AgentRunInput("r2", 60));

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(activities.invocations).hasValue(2);
    }

    @Test
    void aPersistentErrorFailsAfterTwoAttemptsAndIsRecorded() {
        activities.mode = FakeAgentRunActivities.Mode.ALWAYS_FAIL;

        AgentRunWorkflow.AgentRunOutcome outcome = stub("r3").run(new AgentRunWorkflow.AgentRunInput("r3", 60));

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.error()).isEqualTo("provider returned 503");
        assertThat(activities.invocations).hasValue(2);
        assertThat(activities.events).containsExactly("failed:r3:provider returned 503");
    }

    @Test
    void aRejectionIsNotRetried() {
        activities.mode = FakeAgentRunActivities.Mode.REJECT;

        AgentRunWorkflow.AgentRunOutcome outcome = stub("r4").run(new AgentRunWorkflow.AgentRunInput("r4", 60));

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(activities.invocations).hasValue(1);
        assertThat(activities.events).containsExactly("failed:r4:input \"input\" is required");
    }

    @Test
    void cancellationMarksTheRunWithoutWaitingForTheModelCall() throws Exception {
        activities.mode = FakeAgentRunActivities.Mode.BLOCK;
        AgentRunWorkflow wf = stub("r5");
        WorkflowClient.start(wf::run, new AgentRunWorkflow.AgentRunInput("r5", 600));
        assertThat(activities.invoked.await(10, TimeUnit.SECONDS)).isTrue();

        WorkflowStub untyped = WorkflowStub.fromTyped(wf);
        untyped.cancel();

        assertThatThrownBy(() -> untyped.getResult(10, TimeUnit.SECONDS, AgentRunWorkflow.AgentRunOutcome.class))
                .isInstanceOf(WorkflowFailedException.class)
                .hasCauseInstanceOf(CanceledFailure.class);
        activities.release.countDown();
        testEnv.sleep(Duration.ofSeconds(1));
        assertThat(activities.events).startsWith("cancelled:r5");
    }

    @Test
    void anApprovedWriteResumesTheRunWithoutEscalating() throws Exception {
        activities.mode = FakeAgentRunActivities.Mode.APPROVAL_THEN_SUCCEED;
        AgentRunWorkflow wf = stub("a1");
        WorkflowClient.start(wf::run, new AgentRunWorkflow.AgentRunInput("a1", 60));
        assertThat(activities.paused.await(10, TimeUnit.SECONDS)).isTrue();

        wf.approvalDecided("ap-1");

        assertThat(WorkflowStub.fromTyped(wf).getResult(10, TimeUnit.SECONDS, AgentRunWorkflow.AgentRunOutcome.class).status())
                .isEqualTo("SUCCEEDED");
        assertThat(activities.events).containsExactly("awaiting:a1", "succeeded:a1");
    }

    @Test
    void anUndecidedApprovalEscalatesThenExpiresAndIsNeverApprovedByTime() {
        activities.mode = FakeAgentRunActivities.Mode.APPROVAL_THEN_SUCCEED;

        // The test environment skips time while the workflow waits: 60 min escalation, 1440 min expiry.
        AgentRunWorkflow.AgentRunOutcome outcome = stub("a2").run(new AgentRunWorkflow.AgentRunInput("a2", 60));

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(activities.events).containsExactly("awaiting:a2", "escalated:ap-1", "expired:ap-1", "succeeded:a2");
    }

    @Test
    void aDecisionWhoseSignalWasLostIsPickedUpAtEscalation() {
        activities.mode = FakeAgentRunActivities.Mode.APPROVAL_THEN_SUCCEED;
        activities.approvalStatusAtEscalation = "APPROVED";

        stub("a3").run(new AgentRunWorkflow.AgentRunInput("a3", 60));

        assertThat(activities.events).containsExactly("awaiting:a3", "escalated:ap-1", "succeeded:a3");
    }

    @Test
    void anUnknownOutcomeWaitsForTheOperatorWithoutADeadline() throws Exception {
        activities.mode = FakeAgentRunActivities.Mode.OPERATOR_THEN_SUCCEED;
        AgentRunWorkflow wf = stub("o1");
        WorkflowClient.start(wf::run, new AgentRunWorkflow.AgentRunInput("o1", 60));
        assertThat(activities.paused.await(10, TimeUnit.SECONDS)).isTrue();

        testEnv.sleep(Duration.ofDays(3));
        assertThat(activities.events).containsExactly("operator:o1");
        wf.effectResolved("ef-1");

        assertThat(WorkflowStub.fromTyped(wf).getResult(10, TimeUnit.SECONDS, AgentRunWorkflow.AgentRunOutcome.class).status())
                .isEqualTo("SUCCEEDED");
        assertThat(activities.events).containsExactly("operator:o1", "succeeded:o1");
    }

    @Test
    void cancellingARunThatAwaitsApprovalMarksItCancelled() throws Exception {
        activities.mode = FakeAgentRunActivities.Mode.APPROVAL_THEN_SUCCEED;
        AgentRunWorkflow wf = stub("a4");
        WorkflowClient.start(wf::run, new AgentRunWorkflow.AgentRunInput("a4", 60));
        assertThat(activities.paused.await(10, TimeUnit.SECONDS)).isTrue();

        WorkflowStub.fromTyped(wf).cancel();

        assertThatThrownBy(() -> WorkflowStub.fromTyped(wf).getResult(10, TimeUnit.SECONDS, AgentRunWorkflow.AgentRunOutcome.class))
                .isInstanceOf(WorkflowFailedException.class);
        assertThat(activities.events).containsExactly("awaiting:a4", "cancelled:a4");
    }

    @Test
    void aRemoteAgentWaitingForInputResumesWhenAnOperatorReplies() throws Exception {
        activities.mode = FakeAgentRunActivities.Mode.INPUT_THEN_SUCCEED;
        AgentRunWorkflow wf = stub("i1");
        WorkflowClient.start(wf::run, new AgentRunWorkflow.AgentRunInput("i1", 60));
        assertThat(activities.paused.await(10, TimeUnit.SECONDS)).isTrue();

        testEnv.sleep(Duration.ofDays(2));
        assertThat(activities.events).containsExactly("input:i1");
        wf.inputProvided("msg-1");

        assertThat(WorkflowStub.fromTyped(wf).getResult(10, TimeUnit.SECONDS, AgentRunWorkflow.AgentRunOutcome.class).status())
                .isEqualTo("SUCCEEDED");
        assertThat(activities.events).containsExactly("input:i1", "succeeded:i1");
    }

    @Test
    void withNoReplyTheRemoteTaskIsAbandonedAndTheRunFails() throws Exception {
        activities.mode = FakeAgentRunActivities.Mode.INPUT_THEN_SUCCEED;
        AgentRunWorkflow wf = stub("i2");
        WorkflowClient.start(wf::run, new AgentRunWorkflow.AgentRunInput("i2", 60));
        assertThat(activities.paused.await(10, TimeUnit.SECONDS)).isTrue();

        testEnv.sleep(AgentRunWorkflowImpl.INPUT_WAIT.plusMinutes(1));

        AgentRunWorkflow.AgentRunOutcome outcome = WorkflowStub.fromTyped(wf)
                .getResult(10, TimeUnit.SECONDS, AgentRunWorkflow.AgentRunOutcome.class);
        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.error()).contains("no reply");
        assertThat(activities.events).containsExactly("input:i2", "input-expired:i2");
    }

    @Test
    void authRequiredWaitsUntilTheRunIsCancelled() throws Exception {
        activities.mode = FakeAgentRunActivities.Mode.AUTH_REQUIRED;
        AgentRunWorkflow wf = stub("u1");
        WorkflowClient.start(wf::run, new AgentRunWorkflow.AgentRunInput("u1", 60));
        assertThat(activities.paused.await(10, TimeUnit.SECONDS)).isTrue();
        testEnv.sleep(Duration.ofDays(30));
        assertThat(activities.events).containsExactly("auth:u1");

        WorkflowStub.fromTyped(wf).cancel();

        assertThatThrownBy(() -> WorkflowStub.fromTyped(wf).getResult(10, TimeUnit.SECONDS, AgentRunWorkflow.AgentRunOutcome.class))
                .isInstanceOf(WorkflowFailedException.class);
        assertThat(activities.events).containsExactly("auth:u1", "cancelled:u1");
    }
}
