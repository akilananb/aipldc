package ai.pdlc.core.domain;

/**
 * Identifies a work item on a specific board profile. {@code boardId} is the provider's native
 * id (e.g. an ADO work item id) as a string.
 */
public record WorkItemRef(String profile, String boardId) {
    public WorkItemRef {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("profile must not be blank");
        }
        if (boardId == null || boardId.isBlank()) {
            throw new IllegalArgumentException("boardId must not be blank");
        }
    }

    /** The Temporal workflow id for the FeatureWorkflow of this item. */
    public String workflowId() {
        return "feature-" + profile + "-" + boardId;
    }
}
