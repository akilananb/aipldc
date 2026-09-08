package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.domain.GrillHandoff;

import java.util.List;

/** Wire shape of {@code GET /api/items/{id}/grill}. */
public record GrillQuestionsDto(boolean resolved, List<GrillQuestionDto> questions) {

    public static GrillQuestionsDto from(GrillHandoff grill) {
        if (grill == null) {
            return new GrillQuestionsDto(false, List.of());
        }
        return new GrillQuestionsDto(grill.allQuestionsResolved(),
                grill.questions().stream().map(GrillQuestionDto::from).toList());
    }
}
