package ai.pdlc.agents.grill;

import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.workflow.BoardCommentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit-park answer parsing: {@link GrillAgent#evaluateAnswers} marks a question answered or
 * parked only when a comment explicitly references its id; an unreferenced question stays open. */
class GrillAgentAnswerParsingTest {

    private static final Handoff ENVELOPE = new Handoff("grill-agent", "po-agent", "4412",
            CanonicalState.NEEDS_CLARIFICATION, List.of(), 0.8, List.of(), List.of());

    @Test
    void oneCommentAnswersOneParksOneAndLeavesAlreadyAnsweredUntouched() {
        GrillQuestion q1 = new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "Which orders?", "evidence", GrillQuestion.Status.OPEN, null, null);
        GrillQuestion q2 = new GrillQuestion("q2", GrillQuestion.Category.USERS, "Which roles?", "evidence", GrillQuestion.Status.OPEN, null, null);
        GrillQuestion q3 = new GrillQuestion("q3", GrillQuestion.Category.NFR, "How many rows?", "evidence", GrillQuestion.Status.OPEN, null, null);
        GrillQuestion q4 = new GrillQuestion("q4", GrillQuestion.Category.RISK, "PII?", "docs/constraints.md", GrillQuestion.Status.ANSWERED, "logged and rate-limited", "PO");
        GrillHandoff previous = new GrillHandoff(ENVELOPE, "story", List.of(q1, q2, q3, q4), List.of(), List.of());

        GrillHandoff result = GrillAgent.evaluateAnswers(previous,
                List.of(new BoardCommentEvent("c1", "po@acme", "q1: Current view. q2: park Q3: 10k rows")));

        assertThat(result.questions()).hasSize(4);
        GrillQuestion resultQ1 = result.questions().get(0);
        assertThat(resultQ1.status()).isEqualTo(GrillQuestion.Status.ANSWERED);
        assertThat(resultQ1.answer()).isEqualTo("Current view.");
        assertThat(resultQ1.answeredBy()).isEqualTo("po@acme");

        GrillQuestion resultQ2 = result.questions().get(1);
        assertThat(resultQ2.status()).isEqualTo(GrillQuestion.Status.PARKED);
        assertThat(result.parked()).containsExactly("q2");

        GrillQuestion resultQ3 = result.questions().get(2);
        assertThat(resultQ3.status()).isEqualTo(GrillQuestion.Status.ANSWERED);
        assertThat(resultQ3.answer()).isEqualTo("10k rows");

        assertThat(result.questions().get(3)).isEqualTo(q4);
    }

    @Test
    void commentWithoutAnyMarkerLeavesEveryOpenQuestionOpen() {
        GrillQuestion q1 = new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "Which orders?", "evidence", GrillQuestion.Status.OPEN, null, null);
        GrillQuestion q2 = new GrillQuestion("q2", GrillQuestion.Category.USERS, "Which roles?", "evidence", GrillQuestion.Status.OPEN, null, null);
        GrillHandoff previous = new GrillHandoff(ENVELOPE, "story", List.of(q1, q2), List.of(), List.of());

        GrillHandoff result = GrillAgent.evaluateAnswers(previous,
                List.of(new BoardCommentEvent("c1", "po@acme", "Looks good, no changes needed.")));

        assertThat(result.questions()).containsExactly(q1, q2);
        assertThat(result.allQuestionsResolved()).isFalse();
    }
}
