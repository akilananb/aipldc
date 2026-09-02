package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.AgentMentionRequest;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One-shot workflow per @-mention on a review comment: runs the named agent's analysis and stores
 * the markdown draft (pending approval) on the comment.
 */
@WorkflowInterface
public interface AgentMentionWorkflow {

    @WorkflowMethod
    void run(AgentMentionRequest request);
}
