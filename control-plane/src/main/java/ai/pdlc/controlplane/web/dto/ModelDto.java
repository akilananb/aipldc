package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;

/** A catalog model; {@code available} is false (with {@code unavailableReason}) when it is disabled or its connection is revoked or expired. */
public record ModelDto(String id, String connectionId, String providerModel, String displayName, boolean enabled,
                       boolean available, String unavailableReason, OffsetDateTime updatedAt, String updatedBy) {
}
