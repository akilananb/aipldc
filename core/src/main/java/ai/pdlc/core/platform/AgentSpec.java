package ai.pdlc.core.platform;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param runtime      {@code native} (a model call) or {@code a2a} (delegation to a remote A2A agent,
 *                     Phase 2 slice 2.5)
 * @param prompt       Mustache template (restricted: no partials, no delimiter changes)
 * @param variables    every top-level template variable the prompt may reference
 * @param model        authorized model binding plus explicitly allowed fallbacks
 * @param limits       generation limits enforced by the runner
 * @param outputSchema optional JSON Schema the typed output must satisfy
 * @param tools        pinned tool versions (native only, slice 2.1)
 * @param remote       runtime {@code a2a}: the {@code A2A_AGENT} connection and the remote skill (slice 2.5)
 */
public record AgentSpec(
        String description,
        String runtime,
        String prompt,
        List<Variable> variables,
        ModelBinding model,
        Limits limits,
        Map<String, Object> outputSchema,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ToolRef> tools,
        @JsonInclude(JsonInclude.Include.NON_NULL) Remote remote) {

    public static final String RUNTIME_NATIVE = "native";
    public static final String RUNTIME_A2A = "a2a";

    /**
     * Pre-slice-2.1 shape (no tools). Fields added after Phase 1 are omitted from canonical JSON
     * while unset, so every version published before them keeps its content hash.
     */
    public AgentSpec(String description, String runtime, String prompt, List<Variable> variables, ModelBinding model,
                     Limits limits, Map<String, Object> outputSchema) {
        this(description, runtime, prompt, variables, model, limits, outputSchema, null, null);
    }

    /** Slice 2.1-2.4 shape (no remote binding); keeps those versions' hashes. */
    public AgentSpec(String description, String runtime, String prompt, List<Variable> variables, ModelBinding model,
                     Limits limits, Map<String, Object> outputSchema, List<ToolRef> tools) {
        this(description, runtime, prompt, variables, model, limits, outputSchema, tools, null);
    }

    /**
     * Where an {@code a2a} agent delegates (slice 2.5): the connection supplies the remote agent's
     * origin and credentials; {@code skill} is the Agent Card skill id the run asks for. The card is
     * re-read at run time and never trusted for anything but the skill list.
     */
    public record Remote(String connectionId, String skill) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isA2a() {
        return RUNTIME_A2A.equals(runtime);
    }

    /** A pinned, published tool version the agent may call (Phase 2 slice 2.1). */
    public record ToolRef(String tool, Integer version) {
    }

    /** A declared prompt input; required variables are checked before every invocation. */
    public record Variable(String name, String description, boolean required) {
    }

    /** No silent default model: {@code fallbacks} run only because the published agent lists them. */
    public record ModelBinding(String model, List<String> fallbacks) {
    }

    /**
     * {@code maxOutputTokens} is optional (not every provider reports usage); wall time is not.
     * {@code maxModelTurns}/{@code maxToolCalls} bound the tool loop (null = the defaults below).
     */
    public record Limits(Integer maxOutputTokens, Integer timeoutSeconds,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer maxModelTurns,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer maxToolCalls) {

        public static final int DEFAULT_MAX_MODEL_TURNS = 8;
        public static final int DEFAULT_MAX_TOOL_CALLS = 16;

        public Limits(Integer maxOutputTokens, Integer timeoutSeconds) {
            this(maxOutputTokens, timeoutSeconds, null, null);
        }

        public int modelTurns() {
            return maxModelTurns == null ? DEFAULT_MAX_MODEL_TURNS : maxModelTurns;
        }

        public int toolCalls() {
            return maxToolCalls == null ? DEFAULT_MAX_TOOL_CALLS : maxToolCalls;
        }
    }

    public List<ToolRef> toolsOrEmpty() {
        return tools == null ? List.of() : tools;
    }
}
