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

    @QueryMethod
    ReviewState state();

    /** Current grill handoff, including any PO agent follow-ups; {@code null} before the first
     * grill evaluation. */
    @QueryMethod
    GrillHandoff grill();
}
