package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.WorkItemRef;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One workflow per work item — orchestration-decision.md §6 Java sketch, truncated at gate 1 for
 * the pilot (the workflow returns once gate 1 passes; plan agent onward is a later phase).
 */
@WorkflowInterface
public interface FeatureWorkflow {

    @WorkflowMethod
    void run(WorkItemRef item);

    @SignalMethod
    void comment(Comment c);

    @SignalMethod
    void approve(Approval a);

    @SignalMethod
    void requestChanges(String by);

    /** Board comments (e.g. grill answers). */
    @SignalMethod
    void commentAdded(BoardCommentEvent e);

    /** Reviewer signal: park every remaining open grill question (answering the reserved
     * confirmation question, if still open, with {@code proceed}) and proceed to story drafting;
     * honored only once at least two grill rounds have been posted during adaptive intake
     * (ADAPTIVE_GRILL_PLAN.md step 4a) — a signal received earlier is retained and takes effect
     * once the second round is posted. */
    @SignalMethod
    void proceedToStory(String by);

    /** Reviewer signal: retry the currently-blocked reasoning/LLM step (see {@link
     * ReviewState#lastFailure}) — a bounded activity retry budget was exhausted, the workflow
     * durably recorded the failure and blocked in place rather than dying, and this resumes it
     * with every already-collected interview answer/approval/plan-consultation state fully
     * intact. A no-op when nothing is currently blocked. */
    @SignalMethod
    void retryStep(String by);

    @QueryMethod
    ReviewState state();

    /** Current grill handoff, including any PO agent follow-ups; {@code null} before the first
     * grill evaluation. */
    @QueryMethod
    GrillHandoff grill();

    /** Grill frontier rounds posted so far during adaptive intake; 0 before the first. */
    @QueryMethod
    int grillRounds();
}
