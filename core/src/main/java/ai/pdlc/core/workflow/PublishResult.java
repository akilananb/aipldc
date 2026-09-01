package ai.pdlc.core.workflow;

/** Result of a {@link BoardSideEffects} publish call: the story's board id, the artifact version
 * just written, and its sha256 content hash (tech-stack §3.3, shown to checkers before they sign). */
public record PublishResult(String storyBoardId, int version, String contentHash) {
}
