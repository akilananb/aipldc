package ai.pdlc.core.workflow;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

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
 *
 * <p>Slice 2.2 (docs/phase-2-execution-spec.md): an invocation may end paused instead of
 * terminal. {@code AWAITING_APPROVAL} waits for {@link #approvalDecided}; past the approval's
 * escalation time it records an escalation and keeps waiting, and past its expiry it expires the
 * approval - which the next invocation returns to the model as a denial. Nothing is ever approved
 * by time. {@code NEEDS_OPERATOR} waits, without a deadline, for {@link #effectResolved}. Each
 * wait is followed by another invocation, which resumes the run's stored conversation. The loop is
 * behind a {@link Workflow#getVersion} marker so runs started before it replay unchanged.
 */
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    static final Duration MARGIN = Duration.ofSeconds(30);

    private static final ActivityOptions BOOKKEEPING = ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.REASONING)
            .setStartToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
            .build();

    private final Set<String> decidedApprovals = new HashSet<>();
    private final Set<String> resolvedEffects = new HashSet<>();

    @Override
    public void approvalDecided(String approvalId) {
        decidedApprovals.add(approvalId);
    }

    @Override
    public void effectResolved(String effectId) {
        resolvedEffects.add(effectId);
    }

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
        int version = Workflow.getVersion("approvals", Workflow.DEFAULT_VERSION, 1);
        try {
            if (version == Workflow.DEFAULT_VERSION) {
                return invoker.invoke(input.runId());
            }
            while (true) {
                AgentRunOutcome outcome = invoker.invoke(input.runId());
                if (AWAITING_APPROVAL.equals(outcome.status())) {
                    awaitApproval(outcome, bookkeeping);
                } else if (NEEDS_OPERATOR.equals(outcome.status())) {
                    String effectId = outcome.effectId();
                    Workflow.await(() -> resolvedEffects.contains(effectId));
                } else {
                    return outcome;
                }
            }
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

    private void awaitApproval(AgentRunOutcome outcome, AgentRunActivities bookkeeping) {
        String approvalId = outcome.approvalId();
        Duration escalateAfter = Duration.ofMinutes(outcome.escalateAfterMinutes());
        Duration expireAfter = Duration.ofMinutes(outcome.expireAfterMinutes());
        if (Workflow.await(escalateAfter, () -> decidedApprovals.contains(approvalId))) {
            return;
        }
        if (!"PENDING".equals(bookkeeping.escalateApproval(approvalId))) {
            return; // decided while its signal was lost
        }
        if (Workflow.await(expireAfter.minus(escalateAfter), () -> decidedApprovals.contains(approvalId))) {
            return;
        }
        bookkeeping.expireApproval(approvalId);
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
