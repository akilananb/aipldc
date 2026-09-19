package ai.pdlc.controlplane.web.dto;

/** PUT /api/artifacts/{id}/versions/{v}/scenarios/{scenario}/review body — {@code status: meets |
 * not-reviewed}. */
public record ScenarioReviewRequest(String status) {
}
