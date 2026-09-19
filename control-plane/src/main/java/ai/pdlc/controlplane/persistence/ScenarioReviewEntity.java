package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Per-scenario review state for the Preview tab's acceptance-criteria accordion. One row per
 * (artifact, scenario name), upserted (not append-only) so a reviewer can un-mark a scenario -
 * history lives in review_events (kind {@code scenario-reviewed}) and review.md. */
@Table("scenario_reviews")
public record ScenarioReviewEntity(
        @Id UUID id,
        UUID artifactId,
        int version,
        String scenario,
        String status,
        String reviewerSub,
        String role,
        OffsetDateTime at) {

    public static final String MEETS = "meets";
    public static final String NOT_REVIEWED = "not-reviewed";

    public static ScenarioReviewEntity newRow(UUID artifactId, int version, String scenario, String status, String reviewerSub, String role) {
        return new ScenarioReviewEntity(null, artifactId, version, scenario, status, reviewerSub, role, OffsetDateTime.now());
    }

    public ScenarioReviewEntity withStatus(String status, String reviewerSub, String role) {
        return new ScenarioReviewEntity(id, artifactId, version, scenario, status, reviewerSub, role, OffsetDateTime.now());
    }
}
