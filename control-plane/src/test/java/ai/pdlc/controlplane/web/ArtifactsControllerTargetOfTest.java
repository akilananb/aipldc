package ai.pdlc.controlplane.web;

import ai.pdlc.core.domain.Anchor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArtifactsController#targetOf}: the live bug where a line-anchored comment inside a
 * scenario body (nodeType {@code paragraph}, {@code scenario} populated only as
 * {@link ai.pdlc.controlplane.review.CommentReanchorer} drift-detection context) was displayed and
 * grouped by its enclosing scenario name instead of its actual line - silently dropping the line
 * number from the UI on every subsequent fetch (the comment's immediate POST response was correct;
 * only the persisted round-trip via GET /versions/{v} mis-formatted it).
 */
class ArtifactsControllerTargetOfTest {

    @Test
    void nullAnchorReturnsLineZero() {
        assertThat(ArtifactsController.targetOf(null)).isEqualTo("line:0");
    }

    @Test
    void rangeAnchorReturnsLineRangeRegardlessOfScenario() {
        Anchor anchor = Anchor.forRange(11, 13, "text", "paragraph", "kitchen-ticket-shows-item");
        assertThat(ArtifactsController.targetOf(anchor)).isEqualTo("line:11-13");
    }

    @Test
    void headingAnchorReturnsScenarioTarget() {
        Anchor anchor = Anchor.forLine(9, "Scenario: kitchen-ticket-shows-item", "heading", "kitchen-ticket-shows-item");
        assertThat(ArtifactsController.targetOf(anchor)).isEqualTo("scenario:kitchen-ticket-shows-item");
    }

    @Test
    void lineAnchoredCommentInsideAScenarioReturnsItsLineNotTheEnclosingScenario() {
        // The regression case: a single-line anchor (nodeType "paragraph") always carries its
        // enclosing scenario as reanchoring context - that must never override the real line target.
        Anchor anchor = Anchor.forLine(11, "  omit: onions", "paragraph", "kitchen-ticket-shows-item-bun-ingredient-and-spice-customizations");
        assertThat(ArtifactsController.targetOf(anchor)).isEqualTo("line:11");
    }

    @Test
    void lineAnchorWithoutAnEnclosingScenarioReturnsLineTarget() {
        Anchor anchor = Anchor.forLine(3, "some text", "paragraph", null);
        assertThat(ArtifactsController.targetOf(anchor)).isEqualTo("line:3");
    }
}
