package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.platform.AgentSpec;

import java.time.OffsetDateTime;

/** One immutable published version, identified by its number and content hash. */
public record AgentVersionDto(
        String workspaceId,
        String agentId,
        int version,
        String name,
        AgentSpec spec,
        String contentHash,
        OffsetDateTime publishedAt,
        String publishedBy) {
}
