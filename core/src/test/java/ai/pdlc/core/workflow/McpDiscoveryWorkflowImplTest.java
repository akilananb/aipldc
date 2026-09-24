package ai.pdlc.core.workflow;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpDiscoveryWorkflowImplTest {

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        env.close();
    }

    private McpDiscoveryWorkflow start(McpDiscoveryActivities activities) {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(McpDiscoveryWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
        return env.getWorkflowClient().newWorkflowStub(McpDiscoveryWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TaskQueues.REASONING).setWorkflowId("mcp-discovery-test").build());
    }

    @Test
    void returnsTheServersTools() {
        McpDiscoveryWorkflow.DiscoveredTool tool = new McpDiscoveryWorkflow.DiscoveredTool("lookup_order", "Look up",
                Map.of("type", "object"), Map.of(), "sha256:x");

        var result = start(id -> new McpDiscoveryWorkflow.DiscoveryResult(List.of(tool), null)).discover("orders-mcp");

        assertThat(result.tools()).containsExactly(tool);
        assertThat(result.error()).isNull();
    }

    @Test
    void aServerThatCannotBeListedBecomesAnErrorResultAfterOneRetry() {
        AtomicInteger attempts = new AtomicInteger();

        var result = start(id -> {
            attempts.incrementAndGet();
            throw ApplicationFailure.newFailure("server answered 503", "McpError");
        }).discover("orders-mcp");

        assertThat(result.error()).isEqualTo("server answered 503");
        assertThat(result.tools()).isEmpty();
        assertThat(attempts).hasValue(2);
    }
}
