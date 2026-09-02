package ai.pdlc.controlplane.web.dto;

import java.util.UUID;

public record ItemDetailDto(
        UUID id,
        String profile,
        String boardId,
        String kind,
        String title,
        String description,
        String canonicalState,
        Integer latestVersion,
        String latestContentHash,
        ReviewStateDto gate,
        String parentId,
        String qualityVerdict) {
}
