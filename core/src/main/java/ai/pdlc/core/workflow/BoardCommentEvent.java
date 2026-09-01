package ai.pdlc.core.workflow;

/** A raw board comment (e.g. a PO/Squad Lead answer to a grill question), delivered to the workflow
 * via the {@code commentAdded} signal — distinct from the review UI's {@link ai.pdlc.core.domain.Comment}
 * which anchors to a story line/scenario. */
public record BoardCommentEvent(String commentId, String author, String text) {
}
