package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("work_items")
public record WorkItemEntity(
        @Id UUID id,
        String profile,
        String boardProvider,
        String boardId,
        String kind,
        String parentId,
        String canonicalState,
        String specChangePath,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static WorkItemEntity newRow(String profile, String boardProvider, String boardId, String kind,
                                         String parentId, String canonicalState, String specChangePath) {
        OffsetDateTime now = OffsetDateTime.now();
        return new WorkItemEntity(null, profile, boardProvider, boardId, kind, parentId, canonicalState, specChangePath, now, now);
    }

    public WorkItemEntity withCanonicalState(String newState) {
        return new WorkItemEntity(id, profile, boardProvider, boardId, kind, parentId, newState, specChangePath, createdAt, OffsetDateTime.now());
    }

    public WorkItemEntity withSpecChangePath(String path) {
        return new WorkItemEntity(id, profile, boardProvider, boardId, kind, parentId, canonicalState, path, createdAt, OffsetDateTime.now());
    }
}
