package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.domain.GrillQuestion;

/** Wire shape of one grill question for {@code GET /api/items/{id}/grill}. */
public record GrillQuestionDto(String id, String askedBy, String category, String question, String evidence,
                                String status, String answer, String answeredBy) {

    public static GrillQuestionDto from(GrillQuestion q) {
        return new GrillQuestionDto(q.id(), q.askedByPoAgent() ? "po-agent" : "grill-agent",
                q.category().wireValue(), q.question(), q.evidence(), q.status().wireValue(),
                q.answer(), q.answeredBy());
    }
}
