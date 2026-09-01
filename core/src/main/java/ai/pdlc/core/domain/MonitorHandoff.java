package ai.pdlc.core.domain;

import java.util.List;

/**
 * Monitor agent → board (grill agent) handoff — {@code docs/agent-playbook.md} §8 "Produces".
 * Extends the base {@link Handoff} envelope by composition.
 *
 * @param envelope base handoff envelope
 * @param trips    rules that tripped this evaluation pass, each becomes one filed card
 */
public record MonitorHandoff(Handoff envelope, List<Trip> trips) {

    public MonitorHandoff {
        trips = trips == null ? List.of() : List.copyOf(trips);
    }

    public boolean anyTripped() {
        return !trips.isEmpty();
    }

    /**
     * @param ruleId       the {@link MonitorRule#id()} that tripped
     * @param evidence     numbers, window, and correlation the card is filed with
     * @param proposedType {@code bug | feature}
     * @param owner        board role assigned to triage
     */
    public record Trip(String ruleId, String evidence, String proposedType, String owner) {
    }
}
