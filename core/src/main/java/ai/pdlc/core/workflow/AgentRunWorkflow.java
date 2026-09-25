package ai.pdlc.core.workflow;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * A single native agent invocation on the configurable platform (docs/phase-1-execution-spec.md
 * slice 4). Deliberately a separate workflow type from {@link FeatureWorkflow}: PDLC histories are
 * never reinterpreted (configurable-agent-platform.md §10).
 *
 * <p>The input carries only the run id and its deadline - never prompt text, inputs or outputs.
 * The pinned agent version, inputs and output live in the {@code platform_runs} row, which only the
 * activity reads and writes, so sensitive content stays out of workflow history.
 */
@WorkflowInterface
public interface AgentRunWorkflow {

    @WorkflowMethod
    AgentRunOutcome run(AgentRunInput input);

    /**
     * An approval of a paused run's write was decided (docs/phase-2-execution-spec.md slice 2.2).
     * Carries only the id; the decision lives in {@code platform_approvals}.
     */
    @SignalMethod
    void approvalDecided(String approvalId);

    /** An operator resolved an effect whose outcome was unknown; the resolution lives in {@code platform_effects}. */
    @SignalMethod
    void effectResolved(String effectId);

    /**
     * An operator answered a remote agent that is waiting for input (docs/phase-2-execution-spec.md
     * slice 2.5). Carries only the reply's message id; the text lives in {@code platform_run_messages}.
     */
    @SignalMethod
    void inputProvided(String messageId);

    /** {@code timeoutSeconds} is the pinned agent's {@code limits.timeoutSeconds}. */
    record AgentRunInput(String runId, int timeoutSeconds) {
    }

    /**
     * Terminal status ({@code SUCCEEDED}/{@code FAILED}/{@code CANCELLED}) or a pause: status
     * {@link #AWAITING_APPROVAL} with {@code approvalId} and the approval's escalation/expiry
     * minutes, {@link #NEEDS_OPERATOR} with {@code effectId}, or (slice 2.5, a2a runs)
     * {@link #AWAITING_INPUT} / {@link #AWAITING_AUTH} when the remote agent asked for input or
     * for authorization. Ids and durations only; content stays in the run row.
     */
    record AgentRunOutcome(String runId, String status, String error, String approvalId, String effectId,
                           Integer escalateAfterMinutes, Integer expireAfterMinutes) {

        public AgentRunOutcome(String runId, String status, String error) {
            this(runId, status, error, null, null, null, null);
        }

        public static AgentRunOutcome awaitingApproval(String runId, String approvalId, int escalateAfterMinutes,
                                                       int expireAfterMinutes) {
            return new AgentRunOutcome(runId, AWAITING_APPROVAL, null, approvalId, null, escalateAfterMinutes,
                    expireAfterMinutes);
        }

        public static AgentRunOutcome needsOperator(String runId, String effectId) {
            return new AgentRunOutcome(runId, NEEDS_OPERATOR, null, null, effectId, null, null);
        }
    }

    String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    String NEEDS_OPERATOR = "NEEDS_OPERATOR";
    String AWAITING_INPUT = "AWAITING_INPUT";
    String AWAITING_AUTH = "AWAITING_AUTH";
}
