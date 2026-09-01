package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("comments")
public record CommentEntity(
        @Id UUID id,
        UUID artifactId,
        int version,
        String authorSub,
        String role,
        String anchorJson,
        String text,
        String intent,
        boolean blocking,
        Integer resolvedInVersion,
        String agentReply,
        OffsetDateTime createdAt) {

    public static CommentEntity newRow(UUID artifactId, int version, String authorSub, String role,
                                        String anchorJson, String text, String intent, boolean blocking) {
        return new CommentEntity(null, artifactId, version, authorSub, role, anchorJson, text, intent, blocking, null, null, OffsetDateTime.now());
    }

    public CommentEntity resolvedIn(int version, String agentReply) {
        return new CommentEntity(id, artifactId, this.version, authorSub, role, anchorJson, text, intent, blocking, version, agentReply, createdAt);
    }
}
