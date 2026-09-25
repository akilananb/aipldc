package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;

/**
 * A requested WRITE call waiting for (or past) a human decision (docs/phase-2-execution-spec.md
 * slice 2.2). The approval authorizes exactly this tool version and these arguments - identified
 * by {@code argsHash} - in this run. {@code runCreatedBy} cannot approve it.
 */
public record ApprovalDto(
        String id,
        String workspaceId,
        String runId,
        String agentId,
        int agentVersion,
        String runCreatedBy,
        int turn,
        String callId,
        String toolId,
        int toolVersion,
        String method,
        String path,
        String connectionId,
        String argsJson,
        String argsHash,
        String status,
        OffsetDateTime requestedAt,
        OffsetDateTime escalatedAt,
        String decidedBy,
        OffsetDateTime decidedAt,
        String reason) {
}
