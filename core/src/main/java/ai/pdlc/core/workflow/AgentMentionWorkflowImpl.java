package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.AgentMentionRequest;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * {@link AgentMentionWorkflow} implementation. Bounded retries on the LLM call (unlike {@link
 * FeatureWorkflowImpl}'s default unlimited retry) so a persistent failure lands the comment in
 * {@code failed} instead of retrying forever.
 */
public class AgentMentionWorkflowImpl implements AgentMentionWorkflow {

    private static final ActivityOptions AGENT_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.REASONING)
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
            .build();
    private static final ActivityOptions BOARD_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.BOARD)
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .build();

    private final AgentActivities agents = Workflow.newActivityStub(AgentActivities.class, AGENT_OPTIONS);
    private final BoardSideEffects board = Workflow.newActivityStub(BoardSideEffects.class, BOARD_OPTIONS);

    @Override
    public void run(AgentMentionRequest request) {
        try {
            String markdown = agents.mentionAnalyze(request);
            board.saveAgentMentionResult(request.commentId(), markdown, "pending");
        } catch (ActivityFailure e) {
            board.saveAgentMentionResult(request.commentId(),
                    "Agent run failed: " + e.getMessage(), "failed");
        }
    }
}
