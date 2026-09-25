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
                Map.of("x", Map.of("type", "string", "if", Map.of())), "oneOf", List.of())))
                .containsExactlyInAnyOrder(
                        "outputSchema uses unsupported keyword \"oneOf\" (supported: " + OutputSchema.KEYWORDS + ")",
                        "outputSchema.properties.x uses unsupported keyword \"if\" (supported: " + OutputSchema.KEYWORDS + ")");
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

    @Test
    void constraintKeywordsAreEnforcedAndAnnotationsAccepted() throws Exception {
        Map<String, Object> schema = Map.of("type", "object", "$schema", "https://json-schema.org/draft/2020-12/schema",
                "additionalProperties", false,
                "properties", Map.of(
                        "id", Map.of("type", "string", "pattern", "^o-[0-9]+$", "minLength", 3, "maxLength", 8, "title", "Order id"),
                        "qty", Map.of("type", "integer", "minimum", 1, "maximum", 10, "default", 1),
                        "tags", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1, "maxItems", 2),
                        "when", Map.of("type", "string", "format", "date", "examples", List.of("2026-01-01"))));

        assertThat(OutputSchema.unsupported(schema)).isEmpty();
        assertThat(OutputSchema.validate(schema, JSON.readTree("{\"id\":\"o-12\",\"qty\":2,\"tags\":[\"a\"],\"when\":\"x\"}"))).isEmpty();
        assertThat(OutputSchema.validate(schema, JSON.readTree(
                "{\"id\":\"x-123456789\",\"qty\":0,\"tags\":[],\"extra\":true}"))).containsExactlyInAnyOrder(
                "$.id must match ^o-[0-9]+$", "$.id must be at most 8 characters", "$.qty must be >= 1",
                "$.tags must have at least 1 items", "$.extra is not allowed");
    }

    @Test
    void badConstraintValuesAreRejectedAtPublication() {
        assertThat(OutputSchema.unsupported(Map.of("type", "string", "pattern", "(", "minLength", -1, "maximum", "ten",
                "additionalProperties", "no"))).containsExactlyInAnyOrder(
                "outputSchema.pattern is not a valid regular expression",
                "outputSchema.minLength must be a non-negative integer",
                "outputSchema.maximum must be a number",
                "outputSchema.additionalProperties must be a boolean or a schema");
        assertThat(OutputSchema.unsupported(Map.of("type", "string", "pattern", "a".repeat(201)))).hasSize(1);
    }
}
