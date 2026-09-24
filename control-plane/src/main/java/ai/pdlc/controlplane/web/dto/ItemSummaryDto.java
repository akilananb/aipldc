package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ItemSummaryDto(
        UUID id,
        String profile,
        String boardId,
        String kind,
        String title,
        String canonicalState,
        OffsetDateTime updatedAt,
        String parentId,
        String qualityVerdict,
        AgentRunDto activeRun,
        DemoSnapshotDto snapshot) {
}
