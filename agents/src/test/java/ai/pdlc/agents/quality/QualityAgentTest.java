package ai.pdlc.agents.quality;

import ai.pdlc.core.domain.QualityReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QualityAgentTest {

    @Test
    void parsesPassVerdictWithScoreAndFindings() {
        String out = """
                VERDICT: PASS
                SCORE: 85
                ## Findings
                - minor: acceptance criteria could name the exact HTTP status
                ## Assessment
                Solid story overall.
                """;

        QualityReport report = QualityAgent.parse(out, "story");

        assertThat(report.subjectKind()).isEqualTo("story");
        assertThat(report.passed()).isTrue();
        assertThat(report.score()).isEqualTo(85);
        assertThat(report.findings()).containsExactly("minor: acceptance criteria could name the exact HTTP status");
        assertThat(report.reportMd()).isEqualTo(out);
    }

    @Test
    void parsesFailVerdictWithMultipleFindings() {
        String out = """
                VERDICT: FAIL
                SCORE: 40
                ## Findings
                - missing NFR: no stated latency budget
                - scenario "rate-limit" lacks a measurable THEN
                ## Assessment
                Needs another pass before it is INVEST-clean.
                """;

        QualityReport report = QualityAgent.parse(out, "story");

        assertThat(report.passed()).isFalse();
        assertThat(report.score()).isEqualTo(40);
        assertThat(report.findings()).containsExactly(
                "missing NFR: no stated latency budget",
                "scenario \"rate-limit\" lacks a measurable THEN");
    }

    @Test
    void unparseableOutputFailsSafeWithSentinelFinding() {
        String out = "I cannot evaluate this content.";

        QualityReport report = QualityAgent.parse(out, "task");

        assertThat(report.subjectKind()).isEqualTo("task");
        assertThat(report.passed()).isFalse();
        assertThat(report.score()).isEqualTo(0);
        assertThat(report.findings()).containsExactly("quality agent returned unparseable output");
        assertThat(report.reportMd()).isEqualTo(out);
    }

    @Test
    void emptyFindingsSectionYieldsNoFindings() {
        String out = """
                VERDICT: PASS
                SCORE: 95
                ## Findings
                ## Assessment
                Nothing to flag.
                """;

        QualityReport report = QualityAgent.parse(out, "task");

        assertThat(report.passed()).isTrue();
        assertThat(report.findings()).isEmpty();
    }
}
