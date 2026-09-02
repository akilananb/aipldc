package ai.pdlc.core.workflow;

import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;

import java.util.List;
import java.util.Map;

/** {@code @QueryMethod state()} result — {@code {version, approvals, openComments, stage,
 * activeStoryBoardId}}. {@code activeStoryBoardId} is the board id of the story currently driving
 * the pipeline when a feature was split into multiple stories (sequential-in-one-workflow); null
 * until the first story is published. */
public record ReviewState(int version, Map<String, Approval> approvals, List<Comment> openComments,
                           CanonicalState stage, String activeStoryBoardId) {
    public ReviewState {
        approvals = approvals == null ? Map.of() : Map.copyOf(approvals);
        openComments = openComments == null ? List.of() : List.copyOf(openComments);
    }

    /** Every role the given gate requires has a distinct-identity approval, and no comment is
     * still blocking — same predicate {@link FeatureWorkflowImpl#gateSatisfied()} uses internally,
     * exposed read-only for tests and the UI. */
    public boolean satisfiesGate(GateConfig gate) {
        return gate.roles().stream().allMatch(approvals::containsKey) && openComments.isEmpty();
    }
}
