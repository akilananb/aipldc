package ai.pdlc.controlplane.demo;

/**
 * One grill question, mirroring {@code GrillQuestionDto} field names exactly. {@code category} and
 * {@code status} use the lowercase wire values ({@code scope|users|acceptance|risk|dependency|nfr}
 * and {@code open|answered|parked}); {@code askedBy} is {@code grill-agent} for the intake questions.
 * {@code answer}/{@code answeredBy} are null while the question is open.
 */
public record DemoGrillQuestionFixture(
        String id,
        String askedBy,
        String category,
        String question,
        String evidence,
        String status,
        String answer,
        String answeredBy) {
}
