package ai.pdlc.adapters.inmemory;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks two fixes found by a live pipeline run: (1) {@code createItem} must dedup on
 * {@code _idempotencyKey} instead of always minting a new item (a Temporal activity retry of
 * publishTasks/publishStory/etc. otherwise doubles every board card), and (2) the id sequence must
 * be constructor-seedable so a caller backed by durable storage can start it above whatever id is
 * already on record, instead of always restarting at the adapter's fixture floor. */
class InMemoryBoardAdapterTest {

    @Test
    void createItemDedupsOnIdempotencyKey() {
        InMemoryBoardAdapter board = new InMemoryBoardAdapter();
        Map<String, Object> fields = Map.of("title", "Export orders to CSV", "_idempotencyKey", "wf-1:task:T1");

        var first = board.createItem("local", "task", fields, "4412");
        var second = board.createItem("local", "task", fields, "4412");

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void createItemWithoutIdempotencyKeyAlwaysMintsANewItem() {
        InMemoryBoardAdapter board = new InMemoryBoardAdapter();
        Map<String, Object> fields = Map.of("title", "t");

        var first = board.createItem("local", "feature", fields, null);
        var second = board.createItem("local", "feature", fields, null);

        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void startingSequenceIsRespectedInsteadOfTheFixtureFloor() {
        InMemoryBoardAdapter board = new InMemoryBoardAdapter(9999);

        var created = board.createItem("local", "story", Map.of("title", "t"), "4412");

        assertThat(Long.parseLong(created.id())).isGreaterThanOrEqualTo(9999);
    }
}
