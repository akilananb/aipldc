package ai.pdlc.agents.grill;

import ai.pdlc.core.domain.GrillQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GrillAgentRiskRuleTest {

    private static final GrillQuestion SCOPE = new GrillQuestion("q1", GrillQuestion.Category.SCOPE,
            "Which orders?", "evidence", GrillQuestion.Status.OPEN, null, null);

    @Test
    void piiTitleProducesRiskQuestion() {
        assertThat(GrillAgent.riskKeywordHit("Export PII data", "")).isTrue();
        List<GrillQuestion> out = GrillAgent.ensureRiskQuestion(List.of(SCOPE), "Export PII data", "");
        assertThat(out).anyMatch(q -> q.category() == GrillQuestion.Category.RISK);
    }

    @Test
    void paymentTitleProducesRiskQuestion() {
        assertThat(GrillAgent.riskKeywordHit("Payment capture flow", "")).isTrue();
        List<GrillQuestion> out = GrillAgent.ensureRiskQuestion(List.of(SCOPE), "Payment capture flow", "");
        assertThat(out).anyMatch(q -> q.category() == GrillQuestion.Category.RISK);
    }

    @Test
    void authKeywordInDescriptionProducesRiskQuestion() {
        assertThat(GrillAgent.riskKeywordHit("", "Add auth token refresh")).isTrue();
        List<GrillQuestion> out = GrillAgent.ensureRiskQuestion(List.of(SCOPE), "", "Add auth token refresh");
        assertThat(out).anyMatch(q -> q.category() == GrillQuestion.Category.RISK);
    }

    @Test
    void noRiskKeywordDoesNotAddRiskQuestion() {
        assertThat(GrillAgent.riskKeywordHit("Export orders", "csv export")).isFalse();
        List<GrillQuestion> out = GrillAgent.ensureRiskQuestion(List.of(SCOPE), "Export orders", "csv export");
        assertThat(out).noneMatch(q -> q.category() == GrillQuestion.Category.RISK);
        assertThat(out).hasSize(1);
    }

    @Test
    void existingRiskQuestionIsNotDuplicated() {
        GrillQuestion risk = new GrillQuestion("q4", GrillQuestion.Category.RISK,
                "PII?", "evidence", GrillQuestion.Status.OPEN, null, null);
        List<GrillQuestion> out = GrillAgent.ensureRiskQuestion(List.of(SCOPE, risk), "Export PII data", "");
        assertThat(out).filteredOn(q -> q.category() == GrillQuestion.Category.RISK).hasSize(1);
    }
}
