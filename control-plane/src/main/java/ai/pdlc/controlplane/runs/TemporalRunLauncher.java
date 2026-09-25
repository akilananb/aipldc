package ai.pdlc.controlplane.runs;

import ai.pdlc.core.workflow.AgentRunWorkflow;
import ai.pdlc.core.workflow.TaskQueues;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;

/**
 * Starts {@link AgentRunWorkflow} on {@link TaskQueues#REASONING} (hosted by the agents worker),
 * following the idempotent start pattern of {@code ArtifactsController.startMentionWorkflow}: the
 * workflow id is derived from the run id, so a replayed start never creates a second execution.
 */
@Component
public class TemporalRunLauncher implements RunLauncher {

    private final WorkflowClient client;

    public TemporalRunLauncher(WorkflowClient client) {
        this.client = client;
    }

    @Override
    public void start(String workflowId, String runId, int timeoutSeconds) {
        AgentRunWorkflow wf = client.newWorkflowStub(AgentRunWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId(workflowId)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        try {
            WorkflowClient.start(wf::run, new AgentRunWorkflow.AgentRunInput(runId, timeoutSeconds));
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // replayed start: the existing execution owns this run
        }
    }

    @Override
    public void cancel(String workflowId) {
        client.newUntypedWorkflowStub(workflowId).cancel();
    }

    @Override
    public void signalApproval(String workflowId, String approvalId) {
        client.newWorkflowStub(AgentRunWorkflow.class, workflowId).approvalDecided(approvalId);
    }

    @Override
    public void signalEffect(String workflowId, String effectId) {
        client.newWorkflowStub(AgentRunWorkflow.class, workflowId).effectResolved(effectId);
    }

    @Override
    public void signalInput(String workflowId, String messageId) {
        client.newWorkflowStub(AgentRunWorkflow.class, workflowId).inputProvided(messageId);
    }
}
