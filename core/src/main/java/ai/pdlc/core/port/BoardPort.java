package ai.pdlc.core.port;

import ai.pdlc.core.domain.AttachmentRef;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.CommentRef;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;

import java.util.List;
import java.util.Map;

/**
 * Board port — tech-stack §4. Adapters map canonical state ↔ provider state via the profile's
 * {@code board.states} table. {@code CiPort} and {@code MetricsPort} are out of pilot scope and are
 * intentionally omitted here.
 */
public interface BoardPort {

    WorkItem getItem(WorkItemRef ref);

    /** Creates an item under {@code profile}, optionally as a child of {@code parentBoardId}. */
    WorkItem createItem(String profile, String kind, Map<String, Object> fields, String parentBoardId);

    void updateFields(WorkItemRef ref, Map<String, Object> fields);

    /** Adapter maps canonical → provider state. */
    void transition(WorkItemRef ref, CanonicalState state);

    List<ai.pdlc.core.domain.Comment> listComments(WorkItemRef ref);

    CommentRef addComment(WorkItemRef ref, String body, String author);

    void link(WorkItemRef ref, String otherBoardId, String relation);

    AttachmentRef attach(WorkItemRef ref, String fileName, byte[] content);

    /** Normalizes a provider webhook payload into {@code item.created | item.updated | comment.added}. */
    CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent);

    /** For duplicate detection: title/keyword search within the profile's scope. */
    List<WorkItem> search(String profile, String query);
}
