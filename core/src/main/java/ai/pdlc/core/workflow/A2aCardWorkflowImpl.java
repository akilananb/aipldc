package ai.pdlc.core.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.List;

/** {@link A2aCardWorkflow}: one read plus one retry; a failure becomes an error result, not a failed workflow. */
public class A2aCardWorkflowImpl implements A2aCardWorkflow {

    private final A2aCardActivities activities = Workflow.newActivityStub(A2aCardActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.REASONING)
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    @Override
    public CardResult read(String connectionId) {
        try {
            return activities.readCard(connectionId);
        } catch (ActivityFailure e) {
            String message = e.getCause() instanceof ApplicationFailure app ? app.getOriginalMessage()
                    : e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return new CardResult(null, null, false, List.of(), message);
        }
    }
}
