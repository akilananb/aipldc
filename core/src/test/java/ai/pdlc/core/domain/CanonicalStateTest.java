package ai.pdlc.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalStateTest {

    @Test
    void wireValuesMatchPdlcYamlLiteralsExactly() {
        assertThat(CanonicalState.NEW.wireValue()).isEqualTo("new");
        assertThat(CanonicalState.NEEDS_CLARIFICATION.wireValue()).isEqualTo("needs-clarification");
        assertThat(CanonicalState.READY_FOR_STORY.wireValue()).isEqualTo("ready-for-story");
        assertThat(CanonicalState.AWAITING_G1.wireValue()).isEqualTo("awaiting-G1");
        assertThat(CanonicalState.APPROVED.wireValue()).isEqualTo("approved");
        assertThat(CanonicalState.PLANNED.wireValue()).isEqualTo("planned");
        assertThat(CanonicalState.IN_PROGRESS.wireValue()).isEqualTo("in-progress");
        assertThat(CanonicalState.AWAITING_G2.wireValue()).isEqualTo("awaiting-G2");
        assertThat(CanonicalState.AWAITING_G3.wireValue()).isEqualTo("awaiting-G3");
        assertThat(CanonicalState.DONE.wireValue()).isEqualTo("done");
        assertThat(CanonicalState.STALE.wireValue()).isEqualTo("stale");
    }

    @Test
    void fromWireValueRoundTrips() {
        for (CanonicalState state : CanonicalState.values()) {
            assertThat(CanonicalState.fromWireValue(state.wireValue())).isSameAs(state);
        }
    }

    @Test
    void unknownWireValueRejected() {
        assertThatThrownBy(() -> CanonicalState.fromWireValue("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boardStatesMapLookupResolvesProviderStateForEveryCanonicalState() {
        // Mirrors payments-squad's board.states table (tech-stack §4) - every canonical state used by
        // the pilot's gate-1 workflow must have a provider-state mapping the adapter can look up.
        java.util.Map<String, String> states = java.util.Map.ofEntries(
                java.util.Map.entry("new", "New"),
                java.util.Map.entry("needs-clarification", "Needs Clarification"),
                java.util.Map.entry("ready-for-story", "Ready for Story"),
                java.util.Map.entry("awaiting-G1", "Awaiting Approval"),
                java.util.Map.entry("approved", "Approved"),
                java.util.Map.entry("stale", "New")
        );
        for (CanonicalState used : new CanonicalState[]{
                CanonicalState.NEW, CanonicalState.NEEDS_CLARIFICATION, CanonicalState.READY_FOR_STORY,
                CanonicalState.AWAITING_G1, CanonicalState.APPROVED, CanonicalState.STALE}) {
            assertThat(states).as("provider state for %s", used).containsKey(used.wireValue());
        }
    }
}
