package ai.pdlc.core.workflow;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * {@link AgentRunWorkflow} implementation. The model call gets the agent's own deadline (plus a
 * margin for loading and recording) and at most two attempts, so a transient provider error is
 * retried once and a persistent one lands the run in {@code FAILED}. Rejections the activity marks
 * non-retryable (missing required input, tampered definition, unavailable model, output that fails
 * its schema) fail on the first attempt.
 *
 * <p>Cancelling the workflow marks the run {@code CANCELLED} without waiting for the model call
 * ({@link ActivityCancellationType#ABANDON}): a provider call already in flight may still finish,
 * but its result is discarded because the run is no longer {@code RUNNING}. Cancellation never
 * claims to undo a completed call.
 */
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    static final Duration MARGIN = Duration.ofSeconds(30);

    private static final ActivityOptions BOOKKEEPING = ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.REASONING)
            .setStartToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
            .build();

    @Override
    public AgentRunOutcome run(AgentRunInput input) {
        AgentRunActivities invoker = Workflow.newActivityStub(AgentRunActivities.class, ActivityOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setStartToCloseTimeout(Duration.ofSeconds(input.timeoutSeconds()).plus(MARGIN))
                .setCancellationType(ActivityCancellationType.ABANDON)
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(2)
                        .setDoNotRetry(AgentRunActivities.NON_RETRYABLE)
                        .build())
                .build());
        AgentRunActivities bookkeeping = Workflow.newActivityStub(AgentRunActivities.class, BOOKKEEPING);
        try {
            return invoker.invoke(input.runId());
        } catch (ActivityFailure e) {
            // A workflow cancellation reaches the abandoned activity call as an ActivityFailure
            // wrapping CanceledFailure - that is a cancellation, not a failed run.
            if (e.getCause() instanceof CanceledFailure canceled) {
                return cancelled(input.runId(), bookkeeping, canceled);
            }
            String error = rootMessage(e);
            bookkeeping.markFailed(input.runId(), error);
            return new AgentRunOutcome(input.runId(), "FAILED", error);
        } catch (CanceledFailure e) {
            return cancelled(input.runId(), bookkeeping, e);
        }
    }

    private static AgentRunOutcome cancelled(String runId, AgentRunActivities bookkeeping, CanceledFailure e) {
        Workflow.newDetachedCancellationScope(() -> bookkeeping.markCancelled(runId)).run();
        throw e;
    }

    private static String rootMessage(ActivityFailure e) {
        Throwable cause = e.getCause();
        if (cause instanceof ApplicationFailure app) {
            return app.getOriginalMessage();
        }
        return cause != null && cause.getMessage() != null ? cause.getMessage() : e.getMessage();
    }
}
