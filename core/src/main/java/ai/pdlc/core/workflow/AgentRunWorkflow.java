package ai.pdlc.core.workflow;

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

    /** {@code timeoutSeconds} is the pinned agent's {@code limits.timeoutSeconds}. */
    record AgentRunInput(String runId, int timeoutSeconds) {
    }

    /** Terminal status only; content stays in the run row. */
    record AgentRunOutcome(String runId, String status, String error) {
    }
}
