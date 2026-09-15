package ai.pdlc.controlplane.demo;

import java.util.List;

/**
 * The feature owner's grill clarification — mirrors {@code GrillQuestionsDto}. {@code resolved} is
 * {@code false} for the stage-06a open questions and {@code true} for every later feature whose
 * questions carry answers.
 */
public record DemoGrillFixture(
        boolean resolved,
        List<DemoGrillQuestionFixture> questions) {
}
