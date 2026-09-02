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
        String agentName,
        String agentResultMd,
        String agentResultStatus,
        String agentResultApprovedBy,
        OffsetDateTime createdAt) {

    public static final String AGENT_RUNNING = "running";
    public static final String AGENT_PENDING = "pending";
    public static final String AGENT_APPROVED = "approved";
    public static final String AGENT_FAILED = "failed";

    public static CommentEntity newRow(UUID artifactId, int version, String authorSub, String role,
                                        String anchorJson, String text, String intent, boolean blocking) {
        return new CommentEntity(null, artifactId, version, authorSub, role, anchorJson, text, intent, blocking,
                null, null, null, null, null, null, OffsetDateTime.now());
    }

    public CommentEntity resolvedIn(int version, String agentReply) {
        return new CommentEntity(id, artifactId, this.version, authorSub, role, anchorJson, text, intent, blocking,
                version, agentReply, agentName, agentResultMd, agentResultStatus, agentResultApprovedBy, createdAt);
    }

    public CommentEntity withAgentRequest(String agentName) {
        return new CommentEntity(id, artifactId, version, authorSub, role, anchorJson, text, intent, blocking,
                resolvedInVersion, agentReply, agentName, agentResultMd, AGENT_RUNNING, agentResultApprovedBy, createdAt);
    }

    public CommentEntity withAgentResult(String markdown, String status) {
        return new CommentEntity(id, artifactId, version, authorSub, role, anchorJson, text, intent, blocking,
                resolvedInVersion, agentReply, agentName, markdown, status, agentResultApprovedBy, createdAt);
    }

    public CommentEntity withAgentApproved(String approvedBy) {
        return new CommentEntity(id, artifactId, version, authorSub, role, anchorJson, text, intent, blocking,
                resolvedInVersion, agentReply, agentName, agentResultMd, AGENT_APPROVED, approvedBy, createdAt);
    }
}
