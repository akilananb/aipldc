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
 * @param runtime      {@code native} (a model call), {@code a2a} (delegation to a remote A2A agent, Phase 2
 *                     slice 2.5), {@code rest} or {@code grpc} (an existing agent service, slice 2.6)
 * @param prompt       Mustache template (restricted: no partials, no delimiter changes)
 * @param variables    every top-level template variable the prompt may reference
 * @param model        authorized model binding plus explicitly allowed fallbacks
 * @param limits       generation limits enforced by the runner
 * @param outputSchema optional JSON Schema the typed output must satisfy
 * @param tools        pinned tool versions (native only, slice 2.1)
 * @param remote       runtime {@code a2a}: the {@code A2A_AGENT} connection and the remote skill (slice 2.5)
 * @param rest         runtime {@code rest}: the {@code REST_AGENT} connection and the declared call mapping (slice 2.6)
 * @param grpc         runtime {@code grpc}: the {@code GRPC_AGENT} connection, registered descriptors and the one
 *                     method the agent calls (slice 2.6b)
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
        @JsonInclude(JsonInclude.Include.NON_NULL) Remote remote,
        @JsonInclude(JsonInclude.Include.NON_NULL) RestBinding rest,
        @JsonInclude(JsonInclude.Include.NON_NULL) GrpcBinding grpc) {

    public static final String RUNTIME_NATIVE = "native";
    public static final String RUNTIME_A2A = "a2a";
    public static final String RUNTIME_REST = "rest";
    public static final String RUNTIME_GRPC = "grpc";

    /**
     * Pre-slice-2.1 shape (no tools). Fields added after Phase 1 are omitted from canonical JSON
     * while unset, so every version published before them keeps its content hash.
     */
    public AgentSpec(String description, String runtime, String prompt, List<Variable> variables, ModelBinding model,
                     Limits limits, Map<String, Object> outputSchema) {
        this(description, runtime, prompt, variables, model, limits, outputSchema, null, null, null, null);
    }

    /** Slice 2.1-2.4 shape (no remote binding); keeps those versions' hashes. */
    public AgentSpec(String description, String runtime, String prompt, List<Variable> variables, ModelBinding model,
                     Limits limits, Map<String, Object> outputSchema, List<ToolRef> tools) {
        this(description, runtime, prompt, variables, model, limits, outputSchema, tools, null, null, null);
    }

    /** Slice 2.5 shape (no REST binding); keeps those versions' hashes. */
    public AgentSpec(String description, String runtime, String prompt, List<Variable> variables, ModelBinding model,
                     Limits limits, Map<String, Object> outputSchema, List<ToolRef> tools, Remote remote) {
        this(description, runtime, prompt, variables, model, limits, outputSchema, tools, remote, null, null);
    }

    /** Slice 2.6a shape (no gRPC binding); keeps those versions' hashes. */
    public AgentSpec(String description, String runtime, String prompt, List<Variable> variables, ModelBinding model,
                     Limits limits, Map<String, Object> outputSchema, List<ToolRef> tools, Remote remote, RestBinding rest) {
        this(description, runtime, prompt, variables, model, limits, outputSchema, tools, remote, rest, null);
    }

    /**
     * Where an {@code a2a} agent delegates (slice 2.5): the connection supplies the remote agent's
     * origin and credentials; {@code skill} is the Agent Card skill id the run asks for. The card is
     * re-read at run time and never trusted for anything but the skill list.
     */
    public record Remote(String connectionId, String skill) {
    }

    /**
     * How a {@code rest} agent calls an existing HTTP agent service (slice 2.6). {@code sync}: one
     * {@code submit} whose 2xx response is the result. {@code async}: {@code submit} returns a job
     * id at {@code taskIdPointer}, {@code status} is polled every {@code pollSeconds}, and the value
     * at {@code statePointer} means only what {@code states} says (remote value → WORKING,
     * COMPLETED, FAILED or CANCELED) - an unlisted value fails the run rather than being guessed.
     * Pointers are RFC 6901 JSON Pointers; paths are relative to the connection's base URL, and
     * {@code {taskId}} is replaced by the percent-encoded job id. {@code idempotency: HEADER} means
     * the service honours {@code Idempotency-Key}, so a submit whose outcome is unknown may be resent.
     */
    public record RestBinding(String connectionId, String mode, Endpoint submit, Endpoint status, Endpoint cancel,
                              String taskIdPointer, String statePointer, Map<String, String> states,
                              String resultPointer, String errorPointer, Integer pollSeconds, String idempotency) {

        public static final String SYNC = "sync";
        public static final String ASYNC = "async";

        public int pollSecondsOrDefault() {
            return pollSeconds == null ? 2 : pollSeconds;
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean idempotentByHeader() {
            return "HEADER".equals(idempotency);
        }
    }

    /** One HTTP call of a {@link RestBinding}. */
    public record Endpoint(String method, String path) {
    }

    /**
     * How a {@code grpc} agent calls an existing gRPC service (slice 2.6b). {@code descriptorSet} is a
     * base64 {@code FileDescriptorSet} registered with the version (and so part of its hash); only
     * {@code service}/{@code method} in it is ever called - a unary or server-streaming method, never
     * one discovered by reflection. The request message is built from the declared variables (each a
     * top-level request field) plus, when {@code promptField} is set, the rendered prompt.
     * {@code idempotent} declares that the service tolerates a resend (the same {@code idempotency-key}
     * metadata), so a call whose outcome is unknown may be retried; {@code maxMessages} caps a stream.
     */
    public record GrpcBinding(String connectionId, String descriptorSet, String service, String method,
                              String promptField, Boolean idempotent, Integer maxMessages) {

        public static final int DEFAULT_MAX_MESSAGES = 100;

        public int maxMessagesOrDefault() {
            return maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages;
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean resendable() {
            return Boolean.TRUE.equals(idempotent);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isA2a() {
        return RUNTIME_A2A.equals(runtime);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean usesRestRuntime() {
        return RUNTIME_REST.equals(runtime);
    }

    /** Named so Jackson cannot mistake it for the {@code grpc} component's getter (as {@code isRest()} once did). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean usesGrpcRuntime() {
        return RUNTIME_GRPC.equals(runtime);
    }

    /** {@code a2a}, {@code rest} or {@code grpc}: the run delegates through a connection and has no model. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean usesRemoteRuntime() {
        return isA2a() || usesRestRuntime() || usesGrpcRuntime();
    }

    /** The connection a remote runtime delegates through; null for native agents. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String remoteConnectionId() {
        if (isA2a()) {
            return remote == null ? null : remote.connectionId();
        }
        if (usesRestRuntime()) {
            return rest == null ? null : rest.connectionId();
        }
        if (usesGrpcRuntime()) {
            return grpc == null ? null : grpc.connectionId();
        }
        return null;
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
