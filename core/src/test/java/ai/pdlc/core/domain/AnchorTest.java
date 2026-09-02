package ai.pdlc.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnchorTest {

    @Test
    void hashIsDeterministicSha256HexOfTrimmedText() {
        String hash = Anchor.hash("  THEN the next returns 429  ");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(Anchor.hash("THEN the next returns 429"));
    }

    @Test
    void differentTextHashesDiffer() {
        assertThat(Anchor.hash("line a")).isNotEqualTo(Anchor.hash("line b"));
    }

    @Test
    void forLineBuildsAnchorWithComputedHash() {
        Anchor anchor = Anchor.forLine(13, "  WHEN the 11th export happens", "listItem", "rate limit");
        assertThat(anchor.line()).isEqualTo(13);
        assertThat(anchor.nodeType()).isEqualTo("listItem");
        assertThat(anchor.scenario()).isEqualTo("rate limit");
        assertThat(anchor.anchorText()).isEqualTo(Anchor.hash("WHEN the 11th export happens"));
    }

    @Test
    void reAnchorByHashSurvivesLineNumberShift() {
        // Simulates tech-stack §3.2: re-anchor by anchorText first when the line moves in v2.
        String lineText = "GIVEN 10 exports (20 for admin) in the last hour";
        Anchor v1 = Anchor.forLine(13, lineText, "listItem", "rate limit");
        Anchor v2 = Anchor.forLine(15, lineText, "listItem", "rate limit"); // same text, shifted down
        assertThat(v2.anchorText()).isEqualTo(v1.anchorText());
    }

    @Test
    void forRangeNormalizesDegenerateRangesToSingleLine() {
        Anchor range = Anchor.forRange(13, 17, "GIVEN a range", "paragraph", null);
        assertThat(range.line()).isEqualTo(13);
        assertThat(range.endLine()).isEqualTo(17);

        Anchor same = Anchor.forRange(13, 13, "GIVEN a range", "paragraph", null);
        assertThat(same.endLine()).isNull();

        Anchor reversed = Anchor.forRange(13, 4, "GIVEN a range", "paragraph", null);
        assertThat(reversed.endLine()).isNull();
    }
}
