package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.platform.AgentSpec;

import java.time.OffsetDateTime;

/**
 * An agent's registry entry: its editable draft ({@code draftRevision} must be echoed back on the
 * next save or publish) plus which published version new runs currently resolve.
 */
public record AgentDefinitionDto(
        String workspaceId,
        String id,
        String status,
        String draftName,
        AgentSpec draftSpec,
        int draftRevision,
        Integer currentVersion,
        Integer latestVersion,
        OffsetDateTime updatedAt,
        String updatedBy) {
}
