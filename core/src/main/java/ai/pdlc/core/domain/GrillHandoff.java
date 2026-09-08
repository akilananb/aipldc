package ai.pdlc.core.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Grill agent → PO agent handoff — {@code docs/agent-playbook.md} §1 "Produces". Extends the base
 * {@link Handoff} envelope (composition, since Java records cannot extend records) with the grill's
 * own fields.
 *
 * @param envelope       the base handoff envelope
 * @param typeDecision   {@code story | epic | bug | duplicate:#id}
 * @param questions      one per fixed category, at minimum
 * @param parked         question ids explicitly deferred; become "Out of scope" lines in the story
 * @param constraintsHit e.g. {@code [pii, rate-limit]}
 */
public record GrillHandoff(
        Handoff envelope,
        String typeDecision,
        List<GrillQuestion> questions,
        List<String> parked,
        List<String> constraintsHit) {

    public GrillHandoff {
        questions = questions == null ? List.of() : List.copyOf(questions);
        parked = parked == null ? List.of() : List.copyOf(parked);
        constraintsHit = constraintsHit == null ? List.of() : List.copyOf(constraintsHit);
    }

    /** True once every question is {@code answered} or {@code parked} — playbook §1 "Stops". */
    public boolean allQuestionsResolved() {
        return questions.stream().noneMatch(q -> q.status() == GrillQuestion.Status.OPEN);
    }

    public List<GrillQuestion> openQuestions() {
        return questions.stream().filter(q -> q.status() == GrillQuestion.Status.OPEN).toList();
    }

    /** Appends the PO agent's follow-up questions (ids {@code po1, po2, …}) to the list. */
    public GrillHandoff withFollowUps(List<GrillQuestion> followUps) {
        List<GrillQuestion> merged = new ArrayList<>(questions);
        merged.addAll(followUps);
        return new GrillHandoff(envelope, typeDecision, merged, parked, constraintsHit);
    }
}
