package ai.pdlc.core.platform;

import java.util.List;
import java.util.Map;

/**
 * The publishable content of a platform {@code AgentDefinition} (configurable-agent-platform.md
 * §4): everything a native runner needs to invoke the agent, and nothing that identifies a
 * workspace, a run or a credential. A published version is an immutable snapshot of this record
 * plus the agent's display name, identified by {@link ContentHash}.
 *
 * <p>Drafts may be incomplete - every field is nullable here and {@link AgentSpecValidator}
 * decides what is publishable.
 *
 * @param description  human-facing summary shown in the catalog
 * @param runtime      {@code native} only in phase 1; external adapters arrive in phase 2
 * @param prompt       Mustache template (restricted: no partials, no delimiter changes)
 * @param variables    every top-level template variable the prompt may reference
 * @param model        authorized model binding plus explicitly allowed fallbacks
 * @param limits       generation limits enforced by the runner
 * @param outputSchema optional JSON Schema the typed output must satisfy
 */
public record AgentSpec(
        String description,
        String runtime,
        String prompt,
        List<Variable> variables,
        ModelBinding model,
        Limits limits,
        Map<String, Object> outputSchema) {

    public static final String RUNTIME_NATIVE = "native";

    /** A declared prompt input; required variables are checked before every invocation. */
    public record Variable(String name, String description, boolean required) {
    }

    /** No silent default model: {@code fallbacks} run only because the published agent lists them. */
    public record ModelBinding(String model, List<String> fallbacks) {
    }

    /** {@code maxOutputTokens} is optional (not every provider reports usage); wall time is not. */
    public record Limits(Integer maxOutputTokens, Integer timeoutSeconds) {
    }
}
