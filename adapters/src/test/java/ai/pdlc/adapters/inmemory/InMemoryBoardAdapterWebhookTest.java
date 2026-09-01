package ai.pdlc.adapters.inmemory;

import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.WorkItemRef;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: a replayed/duplicate {@code item.created} webhook must never reset an item that
 * has already progressed (e.g. gained board comments) - orchestration-decision §5 idempotency.
 * Control-plane's webhook ingress calls {@code onWebhook} to normalize the event BEFORE it checks
 * the dedupe table, so the adapter itself must be idempotent for this case.
 */
class InMemoryBoardAdapterWebhookTest {

    @Test
    void replayedItemCreatedDoesNotWipeExistingComments() {
        InMemoryBoardAdapter board = new InMemoryBoardAdapter();
        Map<String, Object> payload = Map.of(
                "kind", "item.created", "boardId", "4412", "rev", 1L,
                "itemKind", "feature", "title", "Export the filtered orders view to CSV");

        CanonicalEvent first = board.onWebhook("local", payload);
        assertThat(first.kind()).isEqualTo(CanonicalEvent.Kind.ITEM_CREATED);

        WorkItemRef ref = new WorkItemRef("local", "4412");
        board.addComment(ref, "grill questions here", "grill-agent-bot");
        assertThat(board.listComments(ref)).hasSize(1);

        // Replay of the same (or any subsequent) item.created webhook for the same board id.
        board.onWebhook("local", payload);

        assertThat(board.listComments(ref)).hasSize(1);
        assertThat(board.getItem(ref).title()).isEqualTo("Export the filtered orders view to CSV");
    }
}
