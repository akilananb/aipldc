package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("release_documents")
public record ReleaseDocumentEntity(
        @Id UUID id,
        UUID storyId,
        String releaseId,
        String docId,
        String title,
        String content,
        String checkerRole,
        String contentHash,
        int packVersion,
        OffsetDateTime createdAt) {

    public static ReleaseDocumentEntity newRow(UUID storyId, String releaseId, String docId, String title,
                                                String content, String checkerRole, String contentHash, int packVersion) {
        return new ReleaseDocumentEntity(null, storyId, releaseId, docId, title, content, checkerRole, contentHash, packVersion, OffsetDateTime.now());
    }
}
