package ai.pdlc.controlplane.web.dto;

import ai.pdlc.controlplane.persistence.ReleaseDocumentEntity;

public record ReleaseDocumentDto(
        String docId,
        String title,
        String content,
        String checkerRole,
        String contentHash,
        boolean signed) {

    public static ReleaseDocumentDto from(ReleaseDocumentEntity entity, boolean signed) {
        return new ReleaseDocumentDto(entity.docId(), entity.title(), entity.content(), entity.checkerRole(),
                entity.contentHash(), signed);
    }
}
