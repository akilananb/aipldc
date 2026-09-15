package ai.pdlc.controlplane.demo;

/**
 * A comment fixture mirroring {@code ai.pdlc.core.domain.Comment} plus the persisted resolution and
 * agent-mention columns — but without the database-generated UUID. Comments are identified by
 * {@code ordinal} (unique per item); cross-record references are {@code boardId + artifact version
 * + ordinal}. {@code intent} is the lowercase wire value ({@code change|question|note});
 * {@code stage} is {@code grill|story|plan|build|review|release-pack:<doc-id>}.
 *
 * <p>The agent-mention fields ({@code agentName}, {@code agentResultMd}, {@code agentResultStatus},
 * {@code agentResultApprovedBy}) are {@code null} except on the one {@code @qa} mention comment in
 * the stage-09 snapshot.
 */
public record DemoCommentFixture(
        int ordinal,
        String by,
        String role,
        String stage,
        String target,
        String text,
        String intent,
        boolean blocking,
        int version,
        Integer resolvedInVersion,
        String agentName,
        String agentResultMd,
        String agentResultStatus,
        String agentResultApprovedBy) {
}
