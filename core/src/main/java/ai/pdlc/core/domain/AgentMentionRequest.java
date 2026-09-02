package ai.pdlc.core.domain;

/** A reviewer's @-mention of a named agent on a review comment; commentId doubles as the workflow-id suffix. */
public record AgentMentionRequest(String profile, String workItemId, String commentId,
                                  String agentName, String question, String target, String specChangePath) {
}
