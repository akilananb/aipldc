package ai.pdlc.core.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSpecValidatorTest {

    private static final Set<String> MODELS = Set.of("sonnet", "haiku");

    static AgentSpec valid() {
        return new AgentSpec(
                "Labels things",
                "native",
                "Return the label for {{input}}.{{#examples}} e.g. {{text}}{{/examples}}",
                List.of(new AgentSpec.Variable("input", "thing to label", true),
                        new AgentSpec.Variable("examples", null, false)),
                new AgentSpec.ModelBinding("sonnet", List.of("haiku")),
                new AgentSpec.Limits(2_000, 60),
                Map.of("type", "object"));
    }

    @Test
    void acceptsAValidSpec() {
        assertThat(AgentSpecValidator.validate("Labeler", valid(), MODELS)).isEmpty();
    }

    @Test
    void rejectsAModelOutsideTheCatalogInsteadOfFallingBack() {
        AgentSpec spec = withModel(new AgentSpec.ModelBinding("gpt-unknown", List.of()));

        assertThat(AgentSpecValidator.validate("Labeler", spec, MODELS))
                .containsExactly("model \"gpt-unknown\" is not in the authorized model catalog");
    }

    @Test
    void rejectsAFallbackOutsideTheCatalogOrDuplicatingThePrimary() {
        AgentSpec spec = withModel(new AgentSpec.ModelBinding("sonnet", List.of("sonnet", "opus")));

        assertThat(AgentSpecValidator.validate("Labeler", spec, MODELS)).containsExactly(
                "model.fallbacks must be distinct from each other and from model.model",
                "fallback model \"opus\" is not in the authorized model catalog");
    }

    @Test
    void rejectsUndeclaredVariablesPartialsAndDelimiterChanges() {
        AgentSpec spec = withPrompt("{{input}} {{secret.path}} {{> header}} {{=<% %>=}}");

        assertThat(AgentSpecValidator.validate("Labeler", spec, MODELS)).containsExactly(
                "prompt must not use partials ({{> header}})",
                "prompt must not change Mustache delimiters",
                "prompt references undeclared variable \"secret\"");
    }

    @Test
    void checksTripleMustacheAndIgnoresNamesInsideSectionsAndComments() {
        AgentSpec ok = withPrompt("{{{input}}} {{! {{nothing}} }}{{#examples}}{{anything}}{{.}}{{/examples}}");
        AgentSpec bad = withPrompt("{{{raw}}}");

        assertThat(AgentSpecValidator.validate("Labeler", ok, MODELS)).isEmpty();
        assertThat(AgentSpecValidator.validate("Labeler", bad, MODELS))
                .containsExactly("prompt references undeclared variable \"raw\"");
    }

    @Test
    void rejectsUnbalancedSections() {
        assertThat(AgentSpecValidator.validate("L", withPrompt("{{#examples}}x"), MODELS))
                .containsExactly("prompt has an unclosed section {{#examples}}");
        assertThat(AgentSpecValidator.validate("L", withPrompt("x{{/examples}}"), MODELS))
                .containsExactly("prompt has an unbalanced section close {{/examples}}");
    }

    @Test
    void requiresNameRuntimePromptModelAndTimeout() {
        AgentSpec empty = new AgentSpec(null, null, null, null, null, null, null);

        assertThat(AgentSpecValidator.validate(" ", empty, MODELS)).containsExactly(
                "name is required",
                "runtime must be \"native\"",
                "prompt is required",
                "model.model is required",
                "limits.timeoutSeconds is required");
    }

    @Test
    void boundsLimitsAndVariableNames() {
        AgentSpec v = valid();
        AgentSpec spec = new AgentSpec(v.description(), v.runtime(), "hi",
                List.of(new AgentSpec.Variable("bad-name", null, false),
                        new AgentSpec.Variable("dup", null, false),
                        new AgentSpec.Variable("dup", null, false)),
                v.model(), new AgentSpec.Limits(0, 99_999), null);

        assertThat(AgentSpecValidator.validate("L", spec, MODELS)).containsExactly(
                "variable name must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$",
                "duplicate variable \"dup\"",
                "limits.timeoutSeconds must be between 1 and 3600",
                "limits.maxOutputTokens must be between 1 and 200000");
    }

    private static AgentSpec withModel(AgentSpec.ModelBinding model) {
        AgentSpec v = valid();
        return new AgentSpec(v.description(), v.runtime(), v.prompt(), v.variables(), model, v.limits(), v.outputSchema());
    }

    private static AgentSpec withPrompt(String prompt) {
        AgentSpec v = valid();
        return new AgentSpec(v.description(), v.runtime(), prompt, v.variables(), v.model(), v.limits(), v.outputSchema());
    }
}
