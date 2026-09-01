package ai.pdlc.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Canonical work-item state, independent of any board provider. Adapters map this to and
 * from provider-specific state names via {@code pdlc.yaml}'s {@code board.states} table.
 *
 * <p>Wire values are kebab-case and are literals from {@code docs/tech-stack-architecture.md} §4,
 * plus {@code stale}, the grill 5-day escalation state from {@code docs/agent-playbook.md} §1 "Stops".
 */
public enum CanonicalState {
    NEW("new"),
    NEEDS_CLARIFICATION("needs-clarification"),
    READY_FOR_STORY("ready-for-story"),
    AWAITING_G1("awaiting-G1"),
    APPROVED("approved"),
    PLANNED("planned"),
    IN_PROGRESS("in-progress"),
    AWAITING_G2("awaiting-G2"),
    AWAITING_G3("awaiting-G3"),
    DONE("done"),
    STALE("stale");

    private final String wireValue;

    CanonicalState(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    private static final Map<String, CanonicalState> BY_WIRE_VALUE =
            Stream.of(values()).collect(Collectors.toMap(CanonicalState::wireValue, s -> s));

    @JsonCreator
    public static CanonicalState fromWireValue(String wireValue) {
        CanonicalState state = BY_WIRE_VALUE.get(wireValue);
        if (state == null) {
            throw new IllegalArgumentException("Unknown canonical state: " + wireValue);
        }
        return state;
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
