package ai.pdlc.controlplane.temporal;

import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.FeatureWorkflow;
import io.temporal.client.WorkflowClient;
import org.springframework.stereotype.Component;

@Component
public class WorkflowStubs {

    private final WorkflowClient client;

    public WorkflowStubs(WorkflowClient client) {
        this.client = client;
    }

    public FeatureWorkflow featureWorkflow(WorkItemRef ref) {
        return client.newWorkflowStub(FeatureWorkflow.class, ref.workflowId());
    }
}
