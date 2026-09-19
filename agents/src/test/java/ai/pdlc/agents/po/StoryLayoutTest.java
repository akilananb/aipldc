package ai.pdlc.agents.po;

import ai.pdlc.agents.fixtures.DemoFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the revised-story assertions the e2e demo depends on for the {@code po-revise} flow. */
class StoryLayoutTest {

    @Test
    void revisedStoryContainsDemoAssertions() {
        String revised = DemoFixtures.revisedStory();
        assertThat(revised).contains("20 for admin");
        assertThat(revised).contains("filter hash");
        assertThat(revised).contains("row count");
    }
}
