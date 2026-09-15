package ai.pdlc.controlplane.demo;

import ai.pdlc.core.domain.CanonicalState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parse smoke test for the curated {@code demo/restaurant-demo.json} bundle: proves it round-trips
 * through {@code ObjectMapper.readValue(resource, DemoManifest.class)} and matches the approved
 * inventory (9 features incl. live, 7 stories, 25 tasks, 2 releases, 1 bug = 44 work items; 43
 * snapshot-owned rows).
 */
class DemoFixtureParseTest {

    private static final String BASELINE_SHA = "e20025a41f3aa69b22a7904e1d56c6328dccccc2";
    private static final String LIVE_TITLE = "Preserve the customer's customizations on the kitchen ticket";

    private static DemoManifest manifest;

    @BeforeAll
    static void loadFixture() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try (InputStream in = DemoFixtureParseTest.class.getResourceAsStream("/demo/restaurant-demo.json")) {
            assertThat(in).as("restaurant-demo.json must be on the classpath").isNotNull();
            manifest = mapper.readValue(in, DemoManifest.class);
        }
    }

    private static List<DemoWorkItemFixture> allItems() {
        List<DemoWorkItemFixture> items = new ArrayList<>();
        for (DemoSnapshotGroup group : manifest.snapshots()) {
            items.addAll(group.items());
        }
        return items;
    }

    private static List<DemoWorkItemFixture> byKind(String kind) {
        return allItems().stream().filter(i -> i.kind().equals(kind)).toList();
    }

    private static DemoWorkItemFixture item(String boardId) {
        return allItems().stream()
                .filter(i -> i.boardId().equals(boardId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing item " + boardId));
    }

    @Test
    void parsesBundleAndMatchesInventory() {
        assertThat(manifest.version()).isEqualTo("restaurant-v1");
        assertThat(manifest.baselineSha()).isEqualTo(BASELINE_SHA);
        assertThat(manifest.live().boardId()).isEqualTo("demo-live");
        assertThat(manifest.live().title()).isEqualTo(LIVE_TITLE);
        assertThat(manifest.live().areaPath()).isEqualTo("orders");
        assertThat(manifest.live().resolvedAnswer())
                .contains("2026-09-14T19:00:00+07:00");

        assertThat(manifest.snapshots()).hasSize(8);
        assertThat(manifest.snapshots().stream().map(DemoSnapshotGroup::order).toList())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(manifest.snapshots().stream().map(DemoSnapshotGroup::key).toList())
                .containsExactly("06a", "06b", "07", "08a", "08b", "09", "10", "11");

        // Inventory: 43 snapshot rows (8 features + 7 stories + 25 tasks + 2 releases + 1 bug),
        // plus the separate live entry = 44 work items.
        assertThat(byKind("feature")).hasSize(8);
        assertThat(byKind("story")).hasSize(7);
        assertThat(byKind("task")).hasSize(25);
        assertThat(byKind("release")).hasSize(2);
        assertThat(byKind("bug")).hasSize(1);
        assertThat(allItems()).hasSize(43);

        // Only stage 11 carries deploy/monitor replay evidence.
        assertThat(manifest.snapshots().stream().filter(DemoSnapshotGroup::replay).map(DemoSnapshotGroup::key).toList())
                .containsExactly("11");
    }

    @Test
    void storyStatesAndParentsMatchTheTable() {
        assertThat(item("demo-06b-story").state()).isEqualTo(CanonicalState.AWAITING_G1);
        assertThat(item("demo-07-story").state()).isEqualTo(CanonicalState.AWAITING_G1);
        assertThat(item("demo-08a-story").state()).isEqualTo(CanonicalState.PLANNED);
        assertThat(item("demo-08b-story").state()).isEqualTo(CanonicalState.IN_PROGRESS);
        assertThat(item("demo-09-story").state()).isEqualTo(CanonicalState.AWAITING_G2);
        assertThat(item("demo-10-story").state()).isEqualTo(CanonicalState.AWAITING_G3);
        assertThat(item("demo-11-story").state()).isEqualTo(CanonicalState.DONE);

        // Features: 06a needs clarification (no story), the rest ready-for-story.
        assertThat(item("demo-06a-feature").state()).isEqualTo(CanonicalState.NEEDS_CLARIFICATION);
        for (String key : List.of("06b", "07", "08a", "08b", "09", "10", "11")) {
            assertThat(item("demo-" + key + "-feature").state()).isEqualTo(CanonicalState.READY_FOR_STORY);
        }

        // Parent links.
        assertThat(item("demo-06a-feature").parentBoardId()).isNull();
        assertThat(item("demo-07-story").parentBoardId()).isEqualTo("demo-07-feature");
        assertThat(item("demo-09-T1").parentBoardId()).isEqualTo("demo-09-story");
        assertThat(item("demo-10-release").parentBoardId()).isEqualTo("demo-10-story");
        assertThat(item("demo-11-bug").parentBoardId()).isEqualTo("demo-11-story");
    }

    @Test
    void taskFixturesHaveDescriptionsAndNoQualityReports() {
        List<DemoWorkItemFixture> tasks = byKind("task");
        assertThat(tasks).hasSize(25);
        for (DemoWorkItemFixture task : tasks) {
            assertThat(task.description()).isNotBlank();
            assertThat(task.description()).contains("Wave ").contains("Scenario: ")
                    .contains("Touches: src/orders.ts").contains("Test: test/orders.test.ts");
            assertThat(task.versions()).isEmpty();
            assertThat(task.qualityReports()).isEmpty();
            assertThat(task.gate()).isNull();
        }
        // 5 tasks per planning group; 08a new, 08b..11 done.
        for (String key : List.of("08a", "08b", "09", "10", "11")) {
            CanonicalState expected = key.equals("08a") ? CanonicalState.NEW : CanonicalState.DONE;
            assertThat(tasks.stream().filter(t -> t.boardId().startsWith("demo-" + key + "-T")).toList())
                    .hasSize(5)
                    .allSatisfy(t -> assertThat(t.state()).isEqualTo(expected));
        }
    }

    @Test
    void grillIsOpenOnlyForIntakeFeature() {
        assertThat(item("demo-06a-feature").grill().resolved()).isFalse();
        assertThat(item("demo-06a-feature").grill().questions()).hasSize(2);
        assertThat(item("demo-06a-feature").grill().questions())
                .allSatisfy(q -> assertThat(q.status()).isEqualTo("open"));

        for (String key : List.of("06b", "07", "08a", "08b", "09", "10", "11")) {
            DemoGrillFixture grill = item("demo-" + key + "-feature").grill();
            assertThat(grill.resolved()).isTrue();
            assertThat(grill.questions()).hasSize(2);
            assertThat(grill.questions())
                    .allSatisfy(q -> assertThat(q.status()).isEqualTo("answered"));
        }
    }

    @Test
    void awaitingGatesAreNeverPresentedAsPassed() {
        // 06b: G1 reviewing v1, one open blocking comment, no approvals.
        assertThat(item("demo-06b-story").gate().approvals()).isEmpty();
        assertThat(item("demo-06b-story").gate().openBlockingComments()).isEqualTo(1);

        // 07: G1 reviewing v2, only PO approval (SquadLead pending).
        assertThat(item("demo-07-story").gate().approvals()).hasSize(1);
        assertThat(item("demo-07-story").gate().approvals().get(0).role()).isEqualTo("PO");
        assertThat(item("demo-07-story").gate().openBlockingComments()).isZero();

        // 09: G2, only FSDeveloper approval (QA pending).
        assertThat(item("demo-09-story").gate().stage()).isEqualTo("G2");
        assertThat(item("demo-09-story").gate().approvals()).hasSize(1);
        assertThat(item("demo-09-story").gate().approvals().get(0).role()).isEqualTo("FSDeveloper");

        // 10: G3, only two of four documents signed.
        assertThat(item("demo-10-story").gate().stage()).isEqualTo("G3");
        assertThat(item("demo-10-story").gate().approvals()).hasSize(2);
        assertThat(item("demo-10-story").releaseDocuments()).hasSize(4);

        // 11: G3 complete, all four documents signed.
        assertThat(item("demo-11-story").gate().approvals()).hasSize(4);
        assertThat(item("demo-11-story").gate().approvals())
                .allSatisfy(a -> assertThat(a.docId()).isNotNull());
    }

    @Test
    void blockingCommentCarriesResolvedInVersionAfterRevision() {
        // v1 blocking comment: unresolved on 06b, resolved in v2 from 07 onward.
        DemoCommentFixture open = item("demo-06b-story").versions().get(0).comments().get(0);
        assertThat(open.blocking()).isTrue();
        assertThat(open.resolvedInVersion()).isNull();
        assertThat(open.text()).isEqualTo("\"By seven\" has no timezone. Preserve an explicit-offset ISO-8601 deliverBy verbatim and remove unrelated scope.");

        DemoCommentFixture resolved = item("demo-07-story").versions().get(0).comments().get(0);
        assertThat(resolved.blocking()).isTrue();
        assertThat(resolved.resolvedInVersion()).isEqualTo(2);
    }

    @Test
    void eventTimestampsStrictlyIncreaseWithinEachStory() {
        Instant first = Instant.parse("2026-09-14T10:00:00Z");
        for (DemoWorkItemFixture story : byKind("story")) {
            List<DemoEventFixture> events = story.events();
            assertThat(events).isNotEmpty();
            assertThat(events.get(0).timestamp()).isEqualTo(first);
            for (int i = 1; i < events.size(); i++) {
                Instant prev = events.get(i - 1).timestamp();
                Instant cur = events.get(i).timestamp();
                assertThat(cur).as("%s event %d must be >= 1 minute after the previous", story.boardId(), i)
                        .isAfterOrEqualTo(prev.plusSeconds(60));
            }
        }
    }
}
