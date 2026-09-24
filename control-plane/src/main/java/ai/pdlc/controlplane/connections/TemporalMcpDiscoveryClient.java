package ai.pdlc.controlplane.connections;

import ai.pdlc.core.workflow.McpDiscoveryWorkflow;
import ai.pdlc.core.workflow.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link McpDiscoveryWorkflow} on {@link TaskQueues#REASONING} and waits for it: the agents
 * worker resolves the connection's credential and talks to the server; control-plane only sees
 * the tool definitions.
 */
@Component
public class TemporalMcpDiscoveryClient implements McpDiscoveryClient {

    static final Duration WAIT = Duration.ofSeconds(150);

    private final WorkflowClient client;

    public TemporalMcpDiscoveryClient(WorkflowClient client) {
        this.client = client;
    }

    @Override
    public McpDiscoveryWorkflow.DiscoveryResult discover(String connectionId) {
        McpDiscoveryWorkflow wf = client.newWorkflowStub(McpDiscoveryWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId("mcp-discovery-" + connectionId + "-" + UUID.randomUUID())
                .setWorkflowExecutionTimeout(WAIT)
                .build());
        try {
            WorkflowClient.start(wf::discover, connectionId);
            return WorkflowStub.fromTyped(wf).getResult(WAIT.toSeconds(), TimeUnit.SECONDS, McpDiscoveryWorkflow.DiscoveryResult.class);
        } catch (Exception e) {
            return new McpDiscoveryWorkflow.DiscoveryResult(List.of(), "discovery did not complete: " + e.getClass().getSimpleName());
        }
    }
}
