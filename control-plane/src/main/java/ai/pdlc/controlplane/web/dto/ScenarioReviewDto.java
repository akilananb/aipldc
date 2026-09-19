package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;

public record ScenarioReviewDto(String scenario, String status, String by, String role, OffsetDateTime at) {
}
