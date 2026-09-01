package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only: no UPDATE/DELETE in code — tech-stack §5. */
@Table("approvals")
public record ApprovalEntity(
        @Id UUID id,
        UUID artifactId,
        int version,
        String contentHash,
        String authorSub,
        String role,
        String stage,
        OffsetDateTime at) {

    public static ApprovalEntity newRow(UUID artifactId, int version, String contentHash, String authorSub, String role, String stage, OffsetDateTime at) {
        return new ApprovalEntity(null, artifactId, version, contentHash, authorSub, role, stage, at);
    }
}
