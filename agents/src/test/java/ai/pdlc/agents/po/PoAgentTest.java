package ai.pdlc.agents.po;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the fix for the bug where PO story drafts omit the {@code Feature: ... · Area: X} marker
 * {@link StoryParser#area} needs, causing {@code PlanAgent} to silently fall back to a
 * naming-convention default instead of resolving the story's real {@code openspec/config.yaml}
 * area (root-caused after a live build/deploy run touched the wrong file). */
class PoAgentTest {

    @Test
    void injectAreaInsertsRightAfterFirstHeadingAndStoryParserThenResolvesIt() {
        String story = """
                ## Story

                **Title:** Export the filtered orders view to CSV

                **As a** Sales Ops user
                """;

        String withArea = PoAgent.injectArea(story, "orders");

        assertThat(withArea.lines().toList()).containsSequence("## Story", "Area: orders");
        assertThat(StoryParser.area(withArea)).isEqualTo("orders");
    }

    @Test
    void injectAreaPrependsWhenNoHeadingPresent() {
        String story = "**Title:** Export the filtered orders view to CSV\n";

        String withArea = PoAgent.injectArea(story, "orders");

        assertThat(withArea).startsWith("Area: orders\n");
        assertThat(StoryParser.area(withArea)).isEqualTo("orders");
    }

    @Test
    void injectTitlePrependsHeadingWhenStoryHasNoneOfItsOwn() {
        String story = "## Acceptance criteria\nScenario: export\n  GIVEN a filtered view\n  WHEN the user exports\n  THEN a CSV downloads\n";

        String withTitle = PoAgent.injectTitle(story, "Export the filtered orders view to CSV");

        assertThat(withTitle).startsWith("# Export the filtered orders view to CSV\n\n");
        assertThat(StoryParser.title(withTitle)).isEqualTo("Export the filtered orders view to CSV");
    }

    @Test
    void injectedFallbackTitlesAreDisambiguatedPerSplitPartSoChangeSlugsDontCollide() {
        // Mirrors PoAgent.draft's per-part loop: part 0 gets the feature title verbatim, later
        // parts get "(N)" appended - otherwise two split stories that both lack their own title
        // heading would inject the identical title and extractChange would slug them identically.
        String part1 = "## Acceptance criteria\nScenario: export\n  GIVEN x\n  WHEN y\n  THEN z\n";
        String part2 = "## Acceptance criteria\nScenario: restrict\n  GIVEN x\n  WHEN y\n  THEN z\n";
        String featureTitle = "Export the filtered orders view to CSV";

        String story1 = PoAgent.injectTitle(part1, featureTitle);
        String story2 = PoAgent.injectTitle(part2, featureTitle + " (2)");

        assertThat(PoAgent.extractChange(story1)).isNotEqualTo(PoAgent.extractChange(story2));
        assertThat(PoAgent.extractChange(story2)).endsWith("-2");
    }

    @Test
    void parseFollowUpsAssignsSequentialPoIdsAndSkipsUnknownCategory() {
        String output = """
                ===QUESTIONS===
                [
                  {"category": "users", "question": "Which roles may export?", "evidence": "assumption-check"},
                  {"category": "scope", "question": "Only the current filtered view?", "evidence": "q1"},
                  {"category": "weather", "question": "Is it sunny?", "evidence": "assumption-check"}
                ]
                """;

        java.util.List<ai.pdlc.core.domain.GrillQuestion> followUps = PoAgent.parseFollowUps(output, 2);

        assertThat(followUps).extracting(ai.pdlc.core.domain.GrillQuestion::id).containsExactly("po2", "po3");
        assertThat(followUps).extracting(ai.pdlc.core.domain.GrillQuestion::category)
                .containsExactly(ai.pdlc.core.domain.GrillQuestion.Category.USERS, ai.pdlc.core.domain.GrillQuestion.Category.SCOPE);
        assertThat(followUps).allMatch(q -> q.status() == ai.pdlc.core.domain.GrillQuestion.Status.OPEN);
    }

    @Test
    void parseFollowUpsReturnsNullWithoutSentinel() {
        String output = "## Story\n\n**Title:** Export the filtered orders view to CSV\n";

        assertThat(PoAgent.parseFollowUps(output, 1)).isNull();
    }

    @Test
    void nextPoIndexSkipsPastHighestExistingPoNumberEvenWithGaps() {
        // po1 is missing (a prior round's unknown-category dto left a gap) - counting existing po*
        // questions (old, buggy behaviour) would return 3, colliding with the already-used po3.
        ai.pdlc.core.domain.GrillQuestion po2 = new ai.pdlc.core.domain.GrillQuestion("po2",
                ai.pdlc.core.domain.GrillQuestion.Category.USERS, "q", "assumption-check", ai.pdlc.core.domain.GrillQuestion.Status.OPEN, null, null);
        ai.pdlc.core.domain.GrillQuestion po3 = new ai.pdlc.core.domain.GrillQuestion("po3",
                ai.pdlc.core.domain.GrillQuestion.Category.SCOPE, "q", "assumption-check", ai.pdlc.core.domain.GrillQuestion.Status.OPEN, null, null);
        ai.pdlc.core.domain.Handoff envelope = new ai.pdlc.core.domain.Handoff("grill-agent", "po-agent", "4412",
                ai.pdlc.core.domain.CanonicalState.NEEDS_CLARIFICATION, java.util.List.of(), 0.8, java.util.List.of(), java.util.List.of());
        ai.pdlc.core.domain.GrillHandoff grill = new ai.pdlc.core.domain.GrillHandoff(envelope, "story",
                java.util.List.of(po2, po3), java.util.List.of(), java.util.List.of());

        assertThat(PoAgent.nextPoIndex(grill)).isEqualTo(4);
    }

    @Test
    void nextPoIndexReturnsOneForNullOrEmptyGrill() {
        assertThat(PoAgent.nextPoIndex(null)).isEqualTo(1);
    }

    @Test
    void parseFollowUpsStripsJsonCodeFence() {
        String output = """
                ===QUESTIONS===
                ```json
                [
                  {"category": "users", "question": "Which roles may export?", "evidence": "assumption-check"}
                ]
                ```
                """;

        java.util.List<ai.pdlc.core.domain.GrillQuestion> followUps = PoAgent.parseFollowUps(output, 1);

        assertThat(followUps).extracting(ai.pdlc.core.domain.GrillQuestion::id).containsExactly("po1");
    }

    @Test
    void parseFollowUpsSkipsBlankQuestion() {
        String output = """
                ===QUESTIONS===
                [
                  {"category": "users", "question": "  ", "evidence": "assumption-check"},
                  {"category": "scope", "question": "Only the current filtered view?", "evidence": "q1"}
                ]
                """;

        java.util.List<ai.pdlc.core.domain.GrillQuestion> followUps = PoAgent.parseFollowUps(output, 1);

        assertThat(followUps).extracting(ai.pdlc.core.domain.GrillQuestion::id).containsExactly("po2");
    }
}
