package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.platform.ToolSpec;

import java.time.OffsetDateTime;

/** One immutable published tool version; agents pin {@code (toolId, version)}. */
public record ToolVersionDto(
        String workspaceId,
        String toolId,
        int version,
        String name,
        ToolSpec spec,
        String contentHash,
        OffsetDateTime publishedAt,
        String publishedBy) {
}
