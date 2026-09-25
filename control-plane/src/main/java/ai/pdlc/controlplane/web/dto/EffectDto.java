package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;

/**
 * The recorded intent and outcome of one approved write. {@code state} UNKNOWN means the request
 * may have reached the target and the run waits for an operator's {@code resolution}.
 */
public record EffectDto(
        String id,
        String runId,
        String approvalId,
        String toolId,
        int toolVersion,
        String idempotencyKey,
        String state,
        int sendCount,
        Integer httpStatus,
        String resolution,
        String resolvedBy,
        OffsetDateTime resolvedAt,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
