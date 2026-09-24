package ai.pdlc.core.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutputSchemaAndInputsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Object> LABEL = Map.of(
            "type", "object",
            "required", List.of("label"),
            "properties", Map.of(
                    "label", Map.of("type", "string", "enum", List.of("ALPHA", "BETA")),
                    "score", Map.of("type", "number"),
                    "tags", Map.of("type", "array", "items", Map.of("type", "string"))));

    @Test
    void conformingOutputHasNoFindings() throws Exception {
        assertThat(OutputSchema.validate(LABEL, JSON.readTree("{\"label\":\"ALPHA\",\"score\":0.9,\"tags\":[\"a\"]}"))).isEmpty();
    }

    @Test
    void reportsMissingRequiredWrongTypesEnumsAndItems() throws Exception {
        assertThat(OutputSchema.validate(LABEL, JSON.readTree("{\"score\":\"high\",\"tags\":[1]}"))).containsExactlyInAnyOrder(
                "$.label is required", "$.score must be number", "$.tags[0] must be string");
        assertThat(OutputSchema.validate(LABEL, JSON.readTree("{\"label\":\"GAMMA\"}")))
                .containsExactly("$.label must be one of [ALPHA, BETA]");
        assertThat(OutputSchema.validate(LABEL, JSON.readTree("[]"))).containsExactly("$ must be object");
    }

    @Test
    void unsupportedKeywordsAreRejectedAtPublicationNotIgnored() {
        assertThat(OutputSchema.unsupported(LABEL)).isEmpty();
        assertThat(OutputSchema.unsupported(Map.of("type", "object", "properties",
                Map.of("x", Map.of("type", "string", "pattern", "^a")), "oneOf", List.of())))
                .containsExactlyInAnyOrder(
                        "outputSchema uses unsupported keyword \"oneOf\" (supported: " + OutputSchema.KEYWORDS + ")",
                        "outputSchema.properties.x uses unsupported keyword \"pattern\" (supported: " + OutputSchema.KEYWORDS + ")");
        assertThat(OutputSchema.unsupported(Map.of("type", "date"))).hasSize(1);
    }

    @Test
    void inputsMustCoverRequiredVariablesAndNothingElse() {
        AgentSpec spec = AgentSpecValidatorTest.valid();

        assertThat(AgentInputs.validate(spec, Map.of("input", "order 42"))).isEmpty();
        assertThat(AgentInputs.validate(spec, Map.of("input", " ", "secret", "x")))
                .containsExactly("input \"input\" is required", "input \"secret\" is not a declared variable");
        assertThat(AgentInputs.validate(spec, null)).containsExactly("input \"input\" is required");
    }
}
