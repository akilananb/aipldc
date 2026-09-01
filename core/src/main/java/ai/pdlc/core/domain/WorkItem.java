package ai.pdlc.core.domain;

import java.util.List;

/**
 * Provider-independent view of a board work item.
 *
 * @param id          provider-native id (matches {@link WorkItemRef#boardId()})
 * @param kind        canonical kind: {@code feature|story|task|bug}
 * @param title       title / summary
 * @param description description / body
 * @param state       canonical state
 * @param parentId    provider-native id of the parent item, or {@code null}
 * @param areaPath    provider area path / project grouping
 * @param comments    all comments on the item, oldest first
 */
public record WorkItem(
        String id,
        String kind,
        String title,
        String description,
        CanonicalState state,
        String parentId,
        String areaPath,
        List<Comment> comments) {
    public WorkItem {
        comments = comments == null ? List.of() : List.copyOf(comments);
    }
}
