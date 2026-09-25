package ai.pdlc.core.platform;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContentHashTest {

    @Test
    void isStableAcrossMapInsertionOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "object");
        a.put("required", List.of("label"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("required", List.of("label"));
        b.put("type", "object");

        assertThat(ContentHash.ofAgent("L", withSchema(a))).isEqualTo(ContentHash.ofAgent("L", withSchema(b)));
    }

    @Test
    void changesWhenPromptOrNameChanges() {
        AgentSpec v = AgentSpecValidatorTest.valid();
        AgentSpec other = new AgentSpec(v.description(), v.runtime(), v.prompt() + "!", v.variables(), v.model(),
                v.limits(), v.outputSchema());

        assertThat(ContentHash.ofAgent("L", v))
                .startsWith("sha256:")
                .isNotEqualTo(ContentHash.ofAgent("L", other))
                .isNotEqualTo(ContentHash.ofAgent("M", v));
    }

    @Test
    void roundTripsThroughCanonicalJsonWithTheSameHash() {
        AgentSpec v = AgentSpecValidatorTest.valid();

        AgentSpec read = ContentHash.read(ContentHash.canonicalJson(v), AgentSpec.class);

        assertThat(read).isEqualTo(v);
        assertThat(ContentHash.ofAgent("L", read)).isEqualTo(ContentHash.ofAgent("L", v));
    }

    @Test
    void specsWithoutPhase2FieldsKeepTheirPhase1Hash() {
        // Pinned from the Phase 1 code: adding tools / tool-loop limits must not re-hash old versions.
        assertThat(ContentHash.ofAgent("Labeler", AgentSpecValidatorTest.valid()))
                .isEqualTo("sha256:112dcd51f1dc31aeff4984e533145bb62dfb1fbe44c51db08f6d898675a0634d");
        assertThat(ContentHash.canonicalJson(AgentSpecValidatorTest.valid()))
                .doesNotContain("tools", "maxModelTurns", "maxToolCalls");
    }

    @Test
    void toolPinsAndLoopLimitsArePartOfTheHash() {
        AgentSpec v = AgentSpecValidatorTest.valid();
        AgentSpec withTools = new AgentSpec(v.description(), v.runtime(), v.prompt(), v.variables(), v.model(),
                new AgentSpec.Limits(2_000, 60, 4, 8), v.outputSchema(), List.of(new AgentSpec.ToolRef("t1", 1)));
        AgentSpec otherVersion = new AgentSpec(v.description(), v.runtime(), v.prompt(), v.variables(), v.model(),
                new AgentSpec.Limits(2_000, 60, 4, 8), v.outputSchema(), List.of(new AgentSpec.ToolRef("t1", 2)));

        assertThat(ContentHash.ofAgent("L", withTools))
                .isNotEqualTo(ContentHash.ofAgent("L", v))
                .isNotEqualTo(ContentHash.ofAgent("L", otherVersion));
        assertThat(ContentHash.read(ContentHash.canonicalJson(withTools), AgentSpec.class)).isEqualTo(withTools);
    }

    private static AgentSpec withSchema(Map<String, Object> schema) {
        AgentSpec v = AgentSpecValidatorTest.valid();
        return new AgentSpec(v.description(), v.runtime(), v.prompt(), v.variables(), v.model(), v.limits(), schema);
    }
}
