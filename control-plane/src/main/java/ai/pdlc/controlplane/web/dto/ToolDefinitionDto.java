package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.platform.ToolSpec;

import java.time.OffsetDateTime;

/** A tool's registry entry: editable draft plus the published version agents may pin (Phase 2 slice 2.1). */
public record ToolDefinitionDto(
        String workspaceId,
        String id,
        String status,
        String draftName,
        ToolSpec draftSpec,
        int draftRevision,
        Integer currentVersion,
        Integer latestVersion,
        OffsetDateTime updatedAt,
        String updatedBy) {
}
