package ai.pdlc.controlplane.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link AgentMentions#parse} — the trigger for the whole @-mention review flow, so its edge
 * cases (case, punctuation, email-like false positives, unsupported names) matter more than the
 * one-line implementation suggests. */
class AgentMentionsTest {

    @Test
    void parsesEachSupportedAgentCaseInsensitively() {
        assertThat(AgentMentions.parse("@analyst is this feasible?")).contains("analyst");
        assertThat(AgentMentions.parse("@Architect what about boundaries?")).contains("architect");
        assertThat(AgentMentions.parse("@QA any coverage gaps?")).contains("qa");
        assertThat(AgentMentions.parse("@Dev which files change?")).contains("dev");
    }

    @Test
    void devLikeLongerMentionDoesNotParse() {
        assertThat(AgentMentions.parse("@developer please")).isEmpty();
    }

    @Test
    void firstOfMultipleMentionsWins() {
        assertThat(AgentMentions.parse("@qa then also @analyst please")).contains("qa");
    }

    @Test
    void trailingPunctuationStillParses() {
        assertThat(AgentMentions.parse("@Analyst, is this feasible?")).contains("analyst");
    }

    @Test
    void emailLikeMentionPrecededByWordCharDoesNotParse() {
        assertThat(AgentMentions.parse("x@analyst.com is not a mention")).isEmpty();
    }

    @Test
    void unsupportedAgentNameDoesNotParse() {
        assertThat(AgentMentions.parse("@unknown please review")).isEmpty();
    }

    @Test
    void nullTextDoesNotParse() {
        assertThat(AgentMentions.parse(null)).isEmpty();
    }
}
