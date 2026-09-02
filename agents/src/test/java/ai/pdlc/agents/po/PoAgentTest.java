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
}
