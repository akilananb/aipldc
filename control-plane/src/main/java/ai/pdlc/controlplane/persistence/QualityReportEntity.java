package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("quality_reports")
public record QualityReportEntity(
        @Id UUID id,
        UUID workItemId,
        int version,
        String subjectKind,
        String verdict,
        Integer score,
        String reportMd,
        OffsetDateTime createdAt) {

    public static QualityReportEntity newRow(UUID workItemId, int version, String subjectKind, String verdict,
                                              Integer score, String reportMd) {
        return new QualityReportEntity(null, workItemId, version, subjectKind, verdict, score, reportMd, OffsetDateTime.now());
    }
}
