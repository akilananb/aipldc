package ai.pdlc.controlplane.web.dto;

public record QualityReportDto(String verdict, Integer score, String reportMd, int version, String createdAt) {
}
