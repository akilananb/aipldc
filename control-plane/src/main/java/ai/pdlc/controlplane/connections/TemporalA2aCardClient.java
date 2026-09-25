package ai.pdlc.controlplane.connections;

import ai.pdlc.core.workflow.A2aCardWorkflow;
import ai.pdlc.core.workflow.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Runs {@link A2aCardWorkflow} on {@link TaskQueues#REASONING} and waits for it, like {@link TemporalMcpDiscoveryClient}. */
@Component
public class TemporalA2aCardClient implements A2aCardClient {

    static final Duration WAIT = Duration.ofSeconds(150);

    private final WorkflowClient client;

    public TemporalA2aCardClient(WorkflowClient client) {
        this.client = client;
    }

    @Override
    public A2aCardWorkflow.CardResult read(String connectionId) {
        A2aCardWorkflow wf = client.newWorkflowStub(A2aCardWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId("a2a-card-" + connectionId + "-" + UUID.randomUUID())
                .setWorkflowExecutionTimeout(WAIT)
                .build());
        try {
            WorkflowClient.start(wf::read, connectionId);
            return WorkflowStub.fromTyped(wf).getResult(WAIT.toSeconds(), TimeUnit.SECONDS, A2aCardWorkflow.CardResult.class);
        } catch (Exception e) {
            return new A2aCardWorkflow.CardResult(null, null, false, List.of(), "the card read did not complete: " + e.getClass().getSimpleName());
        }
    }
}
