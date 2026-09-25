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
                "runtime must be \"native\", \"a2a\" or \"rest\"",
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

    @Test
    void checksToolPinsAndLoopLimitsStructurally() {
        AgentSpec v = valid();
        AgentSpec spec = new AgentSpec(v.description(), v.runtime(), v.prompt(), v.variables(), v.model(),
                new AgentSpec.Limits(2_000, 60, 0, 65), v.outputSchema(),
                List.of(new AgentSpec.ToolRef("t1", 1), new AgentSpec.ToolRef("t1", 2),
                        new AgentSpec.ToolRef("t2", null), new AgentSpec.ToolRef(" ", 1)));

        assertThat(AgentSpecValidator.validate("Labeler", spec, MODELS)).containsExactly(
                "limits.maxModelTurns must be between 1 and 32",
                "limits.maxToolCalls must be between 1 and 64",
                "tool \"t1\" is listed more than once",
                "tool \"t2\" must pin a published version",
                "tools[].tool is required");
    }

    static AgentSpec remote(String connectionId, String skill) {
        AgentSpec v = valid();
        return new AgentSpec("Delegates", "a2a", "Summarise {{input}}", List.of(new AgentSpec.Variable("input", null, true)),
                null, v.limits(), null, null, new AgentSpec.Remote(connectionId, skill));
    }

    @Test
    void anA2aAgentNamesAConnectionAndSkillAndHasNoModelOrTools() {
        assertThat(AgentSpecValidator.validate("Delegate", remote("partner-agent", "summarise"), MODELS)).isEmpty();

        AgentSpec bad = new AgentSpec("x", "a2a", "Hi {{input}}", List.of(new AgentSpec.Variable("input", null, true)),
                new AgentSpec.ModelBinding("sonnet", List.of()), valid().limits(), null,
                List.of(new AgentSpec.ToolRef("t", 1)), new AgentSpec.Remote("Bad Id", " "));
        assertThat(AgentSpecValidator.validate("Delegate", bad, MODELS)).containsExactly(
                "remote.connectionId must name an A2A_AGENT connection",
                "remote.skill must be a skill id from the remote agent's card",
                "an a2a agent has no model binding; the remote agent chooses its own",
                "an a2a agent has no tools; the remote agent uses its own");

        AgentSpec nativeWithRemote = new AgentSpec(valid().description(), "native", valid().prompt(), valid().variables(),
                valid().model(), valid().limits(), valid().outputSchema(), null, new AgentSpec.Remote("partner-agent", "s"));
        assertThat(AgentSpecValidator.validate("L", nativeWithRemote, MODELS)).containsExactly("remote is only for runtime \"a2a\"");
    }

    @Test
    void theRemoteFieldDoesNotChangeTheHashOfExistingVersions() {
        assertThat(ContentHash.canonicalJson(valid())).doesNotContain("remote");
        assertThat(ContentHash.canonicalJson(remote("partner-agent", "summarise"))).contains("\"remote\":{\"connectionId\":\"partner-agent\"");
    }

    static AgentSpec.RestBinding asyncRest() {
        return new AgentSpec.RestBinding("report-service", "async", new AgentSpec.Endpoint("POST", "/jobs"),
                new AgentSpec.Endpoint("GET", "/jobs/{taskId}"), new AgentSpec.Endpoint("POST", "/jobs/{taskId}/cancel"),
                "/job/id", "/job/state", Map.of("queued", "WORKING", "running", "WORKING", "done", "COMPLETED", "error", "FAILED"),
                "/job/result", "/job/error", 2, "HEADER");
    }

    static AgentSpec rest(AgentSpec.RestBinding binding) {
        return new AgentSpec("Calls the report service", "rest", null, List.of(new AgentSpec.Variable("region", null, true)),
                null, new AgentSpec.Limits(null, 120), null, null, null, binding);
    }

    @Test
    void aRestAgentDeclaresItsWholeMapping() {
        assertThat(AgentSpecValidator.validate("Reports", rest(asyncRest()), MODELS)).isEmpty();
        AgentSpec.RestBinding sync = new AgentSpec.RestBinding("report-service", "sync", new AgentSpec.Endpoint("POST", "/summarise"),
                null, null, null, null, null, "/summary", null, null, null);
        assertThat(AgentSpecValidator.validate("Reports", rest(sync), MODELS)).isEmpty();

        AgentSpec.RestBinding bad = new AgentSpec.RestBinding("Bad", "later", new AgentSpec.Endpoint("GET", "https://evil.example/x"),
                new AgentSpec.Endpoint("GET", "/jobs"), new AgentSpec.Endpoint("PATCH", "/jobs/{taskId}"), "job.id", null,
                Map.of("done", "SUCCEEDED"), null, null, 0, "MAYBE");
        assertThat(AgentSpecValidator.validate("Reports", rest(bad), MODELS)).contains(
                "rest.connectionId must name a REST_AGENT connection",
                "rest.mode must be \"sync\" or \"async\"",
                "rest.submit.method must be one of [POST, PUT]",
                "rest.submit.path must be a relative path starting with /",
                "rest.idempotency must be HEADER or NONE");
        AgentSpec.RestBinding badAsync = new AgentSpec.RestBinding("report-service", "async", new AgentSpec.Endpoint("POST", "/jobs"),
                new AgentSpec.Endpoint("GET", "/jobs"), new AgentSpec.Endpoint("PATCH", "/jobs/{taskId}"), "job.id", null,
                Map.of("done", "SUCCEEDED"), null, null, 0, null);
        assertThat(AgentSpecValidator.validate("Reports", rest(badAsync), MODELS)).containsExactly(
                "rest.status.path must contain {taskId}",
                "rest.cancel.method must be one of [DELETE, POST]",
                "rest.taskIdPointer must be a JSON Pointer such as /job/id",
                "rest.statePointer is required",
                "rest.states[done] must be one of [CANCELED, COMPLETED, FAILED, WORKING]",
                "rest.states must name at least one COMPLETED and one FAILED value",
                "rest.pollSeconds must be between 1 and 60");
    }

    @Test
    void aSyncRestAgentHasNoJobLifecycleAndNoModel() {
        AgentSpec.RestBinding sync = new AgentSpec.RestBinding("report-service", "sync", new AgentSpec.Endpoint("POST", "/summarise"),
                new AgentSpec.Endpoint("GET", "/jobs/{taskId}"), null, null, null, null, null, null, null, null);
        AgentSpec spec = new AgentSpec("x", "rest", null, List.of(), new AgentSpec.ModelBinding("sonnet", List.of()),
                new AgentSpec.Limits(null, 60), null, null, null, sync);

        assertThat(AgentSpecValidator.validate("R", spec, MODELS)).containsExactly(
                "a rest agent has no model binding; the service chooses its own",
                "a sync rest agent has no status, cancel, taskIdPointer, statePointer, states or pollSeconds");
        assertThat(ContentHash.canonicalJson(valid())).doesNotContain("rest");
        assertThat(ContentHash.canonicalJson(remote("partner-agent", "s"))).doesNotContain("\"rest\"");
    }

    @Test
    void aRestBindingSurvivesTheCanonicalJsonRoundTrip() {
        AgentSpec spec = rest(asyncRest());
        String json = ContentHash.canonicalJson(spec);

        assertThat(json).contains("\"rest\":{").doesNotContain("usesRestRuntime");
        AgentSpec read = ContentHash.read(json, AgentSpec.class);
        assertThat(read.rest()).isEqualTo(spec.rest());
        assertThat(ContentHash.ofAgent("r", read)).isEqualTo(ContentHash.ofAgent("r", spec));
        assertThat(AgentSpecValidator.validate("R", read, MODELS)).isEmpty();
    }
}
