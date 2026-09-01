package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("artifacts")
public record ArtifactEntity(
        @Id UUID id,
        UUID workItemId,
        String kind,
        int version,
        String contentHash,
        String gitRef,
        String createdBy,
        OffsetDateTime createdAt) {

    public static ArtifactEntity newRow(UUID workItemId, String kind, int version, String contentHash, String gitRef, String createdBy) {
        return new ArtifactEntity(null, workItemId, kind, version, contentHash, gitRef, createdBy, OffsetDateTime.now());
    }
}
