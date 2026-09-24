package ai.pdlc.core.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.List;

/** {@link McpDiscoveryWorkflow}: one listing attempt plus one retry; a failure becomes an error result, not a failed workflow. */
public class McpDiscoveryWorkflowImpl implements McpDiscoveryWorkflow {

    private final McpDiscoveryActivities activities = Workflow.newActivityStub(McpDiscoveryActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.REASONING)
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    @Override
    public DiscoveryResult discover(String connectionId) {
        try {
            return activities.listTools(connectionId);
        } catch (ActivityFailure e) {
            String message = e.getCause() instanceof ApplicationFailure app ? app.getOriginalMessage()
                    : e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return new DiscoveryResult(List.of(), message);
        }
    }
}
