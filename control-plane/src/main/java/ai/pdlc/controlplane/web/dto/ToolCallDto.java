package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;

/** One recorded tool call of a run: what the model asked for and what policy decided. */
public record ToolCallDto(
        long id,
        int attempt,
        int turn,
        String toolId,
        Integer toolVersion,
        String argsJson,
        String argsHash,
        String decision,
        String reason,
        Integer httpStatus,
        Long durationMs,
        Long responseBytes,
        boolean truncated,
        String error,
        OffsetDateTime createdAt) {
}
