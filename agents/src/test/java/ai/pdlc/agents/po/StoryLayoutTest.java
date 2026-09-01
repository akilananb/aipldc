package ai.pdlc.agents.po;

import ai.pdlc.agents.fixtures.DemoFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the story layout the e2e demo depends on: line 13 is the rate-limit GIVEN line. */
class StoryLayoutTest {

    @Test
    void line13IsRateLimitGiven() {
        List<String> lines = StoryParser.lines(DemoFixtures.story());
        assertThat(lines).hasSizeGreaterThanOrEqualTo(13);
        assertThat(lines.get(12)).isEqualTo("  GIVEN 10 exports in the last hour");
    }

    @Test
    void revisedStoryContainsDemoAssertions() {
        String revised = DemoFixtures.revisedStory();
        assertThat(revised).contains("20 for admin");
        assertThat(revised).contains("filter hash");
        assertThat(revised).contains("row count");
    }
}
