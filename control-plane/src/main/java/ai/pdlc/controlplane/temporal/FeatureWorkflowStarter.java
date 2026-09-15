package ai.pdlc.controlplane.temporal;

import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.FeatureWorkflow;
import ai.pdlc.core.workflow.TaskQueues;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;

/** Starts a {@link FeatureWorkflow} idempotently from a REST/webhook entry point (see
 * {@code orchestration-decision §5}): the workflow id is derived from the item ref and the
 * {@code REJECT_DUPLICATE} reuse policy makes a webhook replay a no-op. */
@Component
public class FeatureWorkflowStarter {

    private final WorkflowClient workflowClient;

    public FeatureWorkflowStarter(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public void start(WorkItemRef itemRef) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId(itemRef.workflowId())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
        FeatureWorkflow stub = workflowClient.newWorkflowStub(FeatureWorkflow.class, options);
        try {
            WorkflowClient.start(stub::run, itemRef);
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            // Idempotent: a webhook replay (or reconciler safety-net event) for the same item is a no-op.
        }
    }
}
