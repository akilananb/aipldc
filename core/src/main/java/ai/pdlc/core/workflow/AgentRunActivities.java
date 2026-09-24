package ai.pdlc.core.workflow;

import io.temporal.activity.ActivityInterface;

/**
 * Hosted by the {@code agents} worker on {@link TaskQueues#REASONING} (its sole poller).
 * {@link #invoke} does the whole native invocation - load the pinned version, verify its hash,
 * check inputs, render, call the model, validate output - and records the result on the run row
 * itself. The bookkeeping methods only ever move a run out of a non-terminal state, so a late
 * result can never overwrite a cancellation (and vice versa).
 */
@ActivityInterface
public interface AgentRunActivities {

    /** Error types that retrying cannot fix; the activity throws them as non-retryable failures. */
    String NON_RETRYABLE = "AgentRunRejected";

    AgentRunWorkflow.AgentRunOutcome invoke(String runId);

    void markFailed(String runId, String error);

    void markCancelled(String runId);
}
