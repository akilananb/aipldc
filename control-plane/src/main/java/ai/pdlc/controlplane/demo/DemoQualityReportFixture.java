package ai.pdlc.controlplane.demo;

import java.util.List;

/**
 * A quality-agent verdict mirroring {@code ai.pdlc.core.domain.QualityReport} plus the artifact
 * {@code version} it evaluated. {@code findings} is empty when {@code passed}. Task items never
 * carry a quality report.
 */
public record DemoQualityReportFixture(
        String subjectKind,
        boolean passed,
        int score,
        int version,
        List<String> findings,
        String reportMd) {
}
