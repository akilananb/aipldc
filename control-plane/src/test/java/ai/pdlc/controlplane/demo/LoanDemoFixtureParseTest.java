package ai.pdlc.controlplane.demo;

import ai.pdlc.core.domain.CanonicalState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parse smoke test for the additive corporate-lending bundle {@code demo/loan-demo.json}: same
 * 8-stage shape as the restaurant bundle (8 features, 7 stories, 25 tasks, 2 releases, 1 bug = 43
 * snapshot rows, no live entry), seeded into its own project with ids, group keys and branch
 * names that never collide with the restaurant bundle.
 */
class LoanDemoFixtureParseTest {

    private static DemoManifest loan;
    private static DemoManifest restaurant;
    private static String loanRaw;

    @BeforeAll
    static void loadFixtures() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try (InputStream in = LoanDemoFixtureParseTest.class.getResourceAsStream("/demo/loan-demo.json")) {
            assertThat(in).as("loan-demo.json must be on the classpath").isNotNull();
            loanRaw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        loan = mapper.readValue(loanRaw, DemoManifest.class);
        try (InputStream in = LoanDemoFixtureParseTest.class.getResourceAsStream("/demo/restaurant-demo.json")) {
            restaurant = mapper.readValue(in, DemoManifest.class);
        }
    }

    private static List<DemoWorkItemFixture> items(DemoManifest m) {
        List<DemoWorkItemFixture> all = new ArrayList<>();
        m.snapshots().forEach(g -> all.addAll(g.items()));
        return all;
    }

    private static DemoWorkItemFixture item(String boardId) {
        return items(loan).stream().filter(i -> i.boardId().equals(boardId)).findFirst()
                .orElseThrow(() -> new AssertionError("missing item " + boardId));
    }

    @Test
    void parsesBundleIntoItsOwnProject() {
        assertThat(loan.version()).isEqualTo("loan-v1");
        assertThat(loan.baselineSha()).isEqualTo(restaurant.baselineSha());
        assertThat(loan.live()).isNull();
        assertThat(loan.project().id()).isEqualTo("corporate-lending");
        assertThat(loan.project().name()).isEqualTo("Corporate Lending");
        assertThat(loan.project().areaPath()).isEqualTo("lending");
        assertThat(restaurant.project()).isNull();

        assertThat(loan.snapshots().stream().map(DemoSnapshotGroup::key).toList())
                .containsExactly("L06a", "L06b", "L07", "L08a", "L08b", "L09", "L10", "L11");
        assertThat(loan.snapshots().stream().map(DemoSnapshotGroup::order).toList())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(items(loan)).hasSize(43);
        assertThat(items(loan).stream().filter(i -> i.kind().equals("task"))).hasSize(25);
        assertThat(items(loan).stream().filter(i -> i.kind().equals("story"))).hasSize(7);
    }

    @Test
    void mirrorsTheRestaurantStateProgression() {
        List<DemoWorkItemFixture> r = items(restaurant);
        List<DemoWorkItemFixture> l = items(loan);
        assertThat(l).hasSameSizeAs(r);
        for (int i = 0; i < r.size(); i++) {
            assertThat(l.get(i).boardId()).isEqualTo(r.get(i).boardId().replaceFirst("^demo-", "loan-"));
            assertThat(l.get(i).kind()).isEqualTo(r.get(i).kind());
            assertThat(l.get(i).state()).isEqualTo(r.get(i).state());
            assertThat(l.get(i).events()).hasSameSizeAs(r.get(i).events());
            assertThat(l.get(i).versions()).hasSameSizeAs(r.get(i).versions());
        }
        assertThat(item("loan-06a-feature").state()).isEqualTo(CanonicalState.NEEDS_CLARIFICATION);
        assertThat(item("loan-11-bug").title()).isEqualTo("Monitor trip: exception-rate");
        assertThat(item("loan-11-bug").description()).contains("18%").contains("Review DSCR rule for SME segment");
    }

    @Test
    void neverCollidesWithTheRestaurantBundle() {
        Set<String> restaurantIds = new HashSet<>();
        items(restaurant).forEach(i -> restaurantIds.add(i.boardId()));
        restaurantIds.add(restaurant.live().boardId());
        assertThat(items(loan)).allSatisfy(i -> assertThat(restaurantIds).doesNotContain(i.boardId()));
        Set<String> restaurantKeys = new HashSet<>();
        restaurant.snapshots().forEach(g -> restaurantKeys.add(g.key()));
        assertThat(loan.snapshots()).allSatisfy(g -> assertThat(restaurantKeys).doesNotContain(g.key()));

        assertThat(DemoInitializer.branchName(restaurant.version(), "06b", 1)).isEqualTo("demo/restaurant-v1/06b/v1");
        assertThat(DemoInitializer.branchName(loan.version(), "L06b", 1)).isEqualTo("demo/loan-v1/L06b/v1");

        String lower = loanRaw.toLowerCase();
        for (String word : List.of("bistro", "restaurant", "kitchen", "orders")) {
            assertThat(lower).as("loan bundle must not mention '%s'", word).doesNotContain(word);
        }
    }

    @Test
    void contentMatchesTheFeature() {
        DemoWorkItemFixture intake = item("loan-06a-feature");
        assertThat(intake.title()).isEqualTo("Automate corporate loan application validation");
        assertThat(intake.grill().resolved()).isFalse();
        assertThat(intake.grill().questions()).hasSize(5).allSatisfy(q -> assertThat(q.status()).isEqualTo("open"));
        DemoGrillFixture answered = item("loan-07-feature").grill();
        assertThat(answered.resolved()).isTrue();
        assertThat(answered.questions()).allSatisfy(q -> {
            assertThat(q.status()).isEqualTo("answered");
            assertThat(q.answeredBy()).isEqualTo("po@lending");
        });

        DemoCommentFixture blocking = item("loan-06b-story").versions().get(0).comments().get(0);
        assertThat(blocking.blocking()).isTrue();
        assertThat(blocking.resolvedInVersion()).isNull();
        assertThat(item("loan-07-story").versions().get(0).comments().get(0).resolvedInVersion()).isEqualTo(2);
        assertThat(item("loan-06b-story").qualityReports().get(0).passed()).isFalse();
        assertThat(item("loan-07-story").qualityReports().get(0).passed()).isTrue();
        assertThat(item("loan-07-story").versions().get(1).files().get("proposal.md"))
                .startsWith("Area: lending").contains("As a credit risk officer");

        assertThat(item("loan-09-story").gate().approvals()).singleElement()
                .satisfies(a -> assertThat(a.who()).isEqualTo("dev@lending"));
        assertThat(item("loan-11-story").gate().approvals()).hasSize(4).allSatisfy(a -> assertThat(a.docId()).isNotNull());
        assertThat(item("loan-11-story").releaseDocuments().stream().map(DemoReleaseDocumentFixture::docId).toList())
                .containsExactly("change-notes", "rollout-plan", "monitor-rules", "test-evidence");
        assertThat(item("loan-11-story").buildEvidence().files()).containsKeys("src/loan-validation.ts", "test/loan-validation.test.ts");

        for (int t = 1; t <= 5; t++) {
            assertThat(item("loan-09-T" + t).description()).contains("- **Area:** lending")
                    .contains("- **Test:** test/loan-validation.test.ts");
        }
    }

    @Test
    void eventTimestampsStrictlyIncreaseWithinEachStory() {
        Instant first = Instant.parse("2026-10-05T10:00:00Z");
        for (DemoWorkItemFixture story : items(loan).stream().filter(i -> i.kind().equals("story")).toList()) {
            List<DemoEventFixture> events = story.events();
            assertThat(events.get(0).timestamp()).isEqualTo(first);
            for (int i = 1; i < events.size(); i++) {
                assertThat(events.get(i).timestamp()).isAfterOrEqualTo(events.get(i - 1).timestamp().plusSeconds(60));
            }
        }
    }
}
