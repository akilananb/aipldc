package ai.pdlc.adapters;

import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract every {@link BoardPort} implementation must satisfy. Concrete subclasses supply a live
 * adapter instance; {@link ai.pdlc.adapters.inmemory.InMemoryBoardAdapter} always runs this, the ADO
 * adapter only when real credentials are configured (env-gated in the subclass).
 */
public abstract class BoardPortContractTest {

    protected abstract BoardPort port();

    protected abstract String profile();

    @Test
    void createItemThenGetItemRoundTrips() {
        BoardPort board = port();
        WorkItem created = board.createItem(profile(), "feature",
                Map.of("title", "Export the filtered orders view to CSV", "description", "CSV export"), null);

        WorkItem fetched = board.getItem(new WorkItemRef(profile(), created.id()));
        assertThat(fetched.title()).isEqualTo("Export the filtered orders view to CSV");
        assertThat(fetched.state()).isEqualTo(CanonicalState.NEW);
    }

    @Test
    void transitionChangesState() {
        BoardPort board = port();
        WorkItem created = board.createItem(profile(), "feature", Map.of("title", "t"), null);
        WorkItemRef ref = new WorkItemRef(profile(), created.id());

        board.transition(ref, CanonicalState.READY_FOR_STORY);

        assertThat(board.getItem(ref).state()).isEqualTo(CanonicalState.READY_FOR_STORY);
    }

    @Test
    void addCommentThenListCommentsRoundTrips() {
        BoardPort board = port();
        WorkItem created = board.createItem(profile(), "feature", Map.of("title", "t"), null);
        WorkItemRef ref = new WorkItemRef(profile(), created.id());

        board.addComment(ref, "current filtered view, max 10k rows", "po@acme");

        List<ai.pdlc.core.domain.Comment> comments = board.listComments(ref);
        assertThat(comments).anyMatch(c -> c.text().contains("current filtered view"));
    }

    @Test
    void searchFindsByTitle() {
        BoardPort board = port();
        board.createItem(profile(), "feature", Map.of("title", "Export the filtered orders view to CSV"), null);

        List<WorkItem> results = board.search(profile(), "filtered orders");

        assertThat(results).anyMatch(w -> w.title().contains("filtered orders"));
    }
}
