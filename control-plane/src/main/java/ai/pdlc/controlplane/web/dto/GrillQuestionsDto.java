package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.GrillHandoff;

import java.util.List;

/** Wire shape of {@code GET /api/items/{id}/grill}. */
public record GrillQuestionsDto(boolean resolved, int rounds, List<GrillQuestionDto> questions) {

    /** {@code resolved} is true only once every question is answered/parked <em>and</em> the
     * workflow has left clarification ({@code stage} is not {@code NEEDS_CLARIFICATION}/{@code
     * STALE}) — between adaptive grill rounds the handoff can be fully resolved while the interview
     * is still in progress (the next round, or a fresh confirmation question, is about to be
     * posted), and that gap must not read as a completed interview on the wire. {@code STALE} is
     * included because {@code awaitAnswers} only resets the stage on entry, so a fold that happens
     * after the stale escalation leaves the stage at {@code STALE} until the next round is posted. */
    public static GrillQuestionsDto from(GrillHandoff grill, CanonicalState stage, int rounds) {
        if (grill == null) {
            return new GrillQuestionsDto(false, rounds, List.of());
        }
        boolean clarifying = stage == CanonicalState.NEEDS_CLARIFICATION || stage == CanonicalState.STALE;
        return new GrillQuestionsDto(grill.allQuestionsResolved() && !clarifying, rounds,
                grill.questions().stream().map(GrillQuestionDto::from).toList());
    }
}
