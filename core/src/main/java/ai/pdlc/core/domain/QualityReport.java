package ai.pdlc.core.domain;

import java.util.List;

/**
 * Quality agent verdict for one story/task draft version — INVEST/clarity/testability evaluation
 * that hard-blocks gate 1 for stories (advisory only for tasks). {@code subjectKind} is
 * {@code "story"} or {@code "task"}; {@code findings} are actionable, one per line, empty when
 * {@code passed}. {@code reportMd} is the full raw agent output, shown verbatim in the UI's Quality
 * tab.
 */
public record QualityReport(String subjectKind, boolean passed, int score, List<String> findings, String reportMd) {
    public QualityReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
