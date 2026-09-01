package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only; mirrors review.md — the two trails must agree (tech-stack §3.3). */
@Table("review_events")
public record ReviewEventEntity(@Id UUID id, UUID workItemId, OffsetDateTime ts, String kind, String payloadJson) {

    public static ReviewEventEntity newRow(UUID workItemId, String kind, String payloadJson) {
        return new ReviewEventEntity(null, workItemId, OffsetDateTime.now(), kind, payloadJson);
    }
}
