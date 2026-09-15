package ai.pdlc.controlplane.adapters;

import ai.pdlc.adapters.localboard.LocalBoardAdapter;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link LocalBoardAdapter} (the {@code local-jdbc} provider) is restart-safe against a
 * real Postgres 16 container with every Flyway migration (including {@code V9__local_board.sql})
 * applied. The volatile {@code InMemoryBoardAdapter} it replaces lost every item/comment on
 * restart; this test drives two adapter instances over the SAME {@link DataSource} (a simulated
 * process restart with no in-memory state) and asserts the durable {@code local_board_*} tables
 * carry every field through.
 *
 * <p>Follows {@code PersistenceIntegrationTest}'s exact wiring: {@code @Testcontainers} +
 * {@code PostgreSQLContainer} + explicit {@code Flyway.configure().load().migrate()} in
 * {@code @BeforeAll} + a minimal {@code @SpringBootTest} context whose only job is to supply the
 * auto-configured {@link DataSource}/{@link JdbcTemplate} pointed at the container. Adapters are
 * constructed directly ({@code new LocalBoardAdapter(dataSource)}), never through a Spring bean.
 */
@Testcontainers
@SpringBootTest(classes = LocalBoardAdapterIntegrationTest.TestApp.class)
class LocalBoardAdapterIntegrationTest {

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        // Run Flyway explicitly (as PersistenceIntegrationTest does) so the schema exists before
        // any adapter touches it. Applies V1..V10, including V9__local_board.sql.
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void releaseContainer() {
        // @Container normally reaps via Ryuk; with TESTCONTAINERS_RYUK_DISABLED=true this explicit
        // stop guarantees the container is released at the end of the class.
        POSTGRES.stop();
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private LocalBoardAdapter newAdapter() {
        return new LocalBoardAdapter(dataSource);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void roundTripSurvivesRecreatingAdapter() {
        LocalBoardAdapter a = newAdapter();
        String profile = "roundtrip";

        WorkItem feature = a.createItem(profile, "feature",
                Map.of("title", "Roundtrip feature", "description", "Feature description", "areaPath", "orders"),
                null);
        WorkItem story = a.createItem(profile, "story",
                Map.of("title", "Roundtrip story", "description", "Story description", "areaPath", "orders"),
                feature.id());
        a.addComment(new WorkItemRef(profile, story.id()), "first comment", "po@bistro");
        a.addComment(new WorkItemRef(profile, story.id()), "second comment", "lead@bistro");
        a.transition(new WorkItemRef(profile, story.id()), CanonicalState.AWAITING_G1);

        // Simulate a process restart: a brand-new adapter over the same DataSource carries no
        // in-memory state. Every field must come back byte-for-byte from Postgres.
        LocalBoardAdapter b = newAdapter();

        WorkItem read = b.getItem(new WorkItemRef(profile, story.id()));
        assertThat(read.title()).isEqualTo("Roundtrip story");
        assertThat(read.description()).isEqualTo("Story description");
        assertThat(read.state()).isEqualTo(CanonicalState.AWAITING_G1);
        assertThat(read.parentId()).isEqualTo(feature.id());
        assertThat(read.areaPath()).isEqualTo("orders");
        assertThat(read.comments())
                .extracting(Comment::text)
                .containsExactly("first comment", "second comment");
        assertThat(read.comments())
                .extracting(Comment::by)
                .containsExactly("po@bistro", "lead@bistro");

        List<Comment> listed = b.listComments(new WorkItemRef(profile, story.id()));
        assertThat(listed).extracting(Comment::text)
                .containsExactly("first comment", "second comment");

        List<WorkItem> results = b.search(profile, "Roundtrip");
        assertThat(results).extracting(WorkItem::title)
                .containsExactlyInAnyOrder("Roundtrip feature", "Roundtrip story");
        WorkItem storyFromSearch = results.stream()
                .filter(w -> w.id().equals(story.id()))
                .findFirst()
                .orElseThrow();
        assertThat(storyFromSearch.state()).isEqualTo(CanonicalState.AWAITING_G1);
        assertThat(storyFromSearch.parentId()).isEqualTo(feature.id());
        assertThat(storyFromSearch.comments())
                .extracting(Comment::text)
                .containsExactly("first comment", "second comment");
    }

    @Test
    void concurrentSameIdempotencyKeyCreatesSingleItem() throws Exception {
        LocalBoardAdapter adapter = newAdapter();
        String profile = "idem";
        Map<String, Object> fields = Map.of("title", "Idempotent item", "_idempotencyKey", "k1");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<WorkItem> f1 = pool.submit(() -> {
                start.await();
                return adapter.createItem(profile, "feature", fields, null);
            });
            Future<WorkItem> f2 = pool.submit(() -> {
                start.await();
                return adapter.createItem(profile, "feature", fields, null);
            });
            start.countDown();

            WorkItem first = f1.get(30, TimeUnit.SECONDS);
            WorkItem second = f2.get(30, TimeUnit.SECONDS);

            // Both racing callers receive the SAME row, and exactly one row exists.
            assertThat(first.id()).isEqualTo(second.id());
            long rows = count(
                    "SELECT COUNT(*) FROM local_board_items WHERE profile = ? AND idempotency_key = ?",
                    profile, "k1");
            assertThat(rows).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void mintedIdCollisionWithImportedBoardIdIsSkippedWithoutOverwriting() {
        LocalBoardAdapter adapter = newAdapter();
        String profile = "collide";

        // The V9 sequence floor is 4412. Reset it so the next auto-mint is deterministically "4412",
        // which is exactly the id we import via webhook below - forcing the collision-retry path.
        jdbcTemplate.execute("ALTER SEQUENCE local_board_id_seq RESTART WITH 4412");

        // Import an explicit numeric board id "4412" (a provider-supplied id) via webhook.
        adapter.onWebhook(profile, Map.of(
                "kind", "item.created",
                "boardId", "4412",
                "itemKind", "feature",
                "title", "Original webhook title",
                "description", "Original webhook description"));

        // First auto-mint is 4412, which collides with the imported row; the adapter must skip it.
        WorkItem minted = adapter.createItem(profile, "feature",
                Map.of("title", "Minted item", "description", "Minted description"), null);

        assertThat(minted.id()).isNotEqualTo("4412");
        assertThat(minted.id()).isEqualTo("4413"); // retried exactly past the collision

        // The imported row is completely untouched by the collision-avoiding createItem.
        WorkItem imported = adapter.getItem(new WorkItemRef(profile, "4412"));
        assertThat(imported.title()).isEqualTo("Original webhook title");
        assertThat(imported.description()).isEqualTo("Original webhook description");
        assertThat(imported.state()).isEqualTo(CanonicalState.NEW);
        assertThat(imported.kind()).isEqualTo("feature");
    }

    @Test
    void duplicateCommentWebhookDedupedAcrossAdapterRecreationButNewRevisionApplies() {
        String profile = "dupreplay";
        String boardId = "dup-item";

        LocalBoardAdapter a = newAdapter();
        a.onWebhook(profile, Map.of("kind", "item.created", "boardId", boardId, "title", "Dup item"));

        Map<String, Object> firstComment = Map.of(
                "kind", "comment.added",
                "boardId", boardId,
                "rev", 2L,
                "text", "first comment",
                "author", "po@bistro");
        a.onWebhook(profile, firstComment);

        // Replay the exact same payload on the SAME adapter instance.
        a.onWebhook(profile, firstComment);
        assertThat(a.listComments(new WorkItemRef(profile, boardId))).hasSize(1);

        // Simulate a restart: a brand-new adapter instance over the same DataSource. The
        // local_board_webhooks ledger must survive (unlike an in-memory dedupe set) and still block
        // the replay.
        LocalBoardAdapter b = newAdapter();
        b.onWebhook(profile, firstComment);
        assertThat(b.listComments(new WorkItemRef(profile, boardId))).hasSize(1);

        // A genuinely new revision must still append - the ledger blocks replays, not progress.
        b.onWebhook(profile, Map.of(
                "kind", "comment.added",
                "boardId", boardId,
                "rev", 3L,
                "text", "second comment",
                "author", "lead@bistro"));
        List<Comment> comments = b.listComments(new WorkItemRef(profile, boardId));
        assertThat(comments).hasSize(2);
        assertThat(comments).extracting(Comment::text)
                .containsExactly("first comment", "second comment");
    }
}
