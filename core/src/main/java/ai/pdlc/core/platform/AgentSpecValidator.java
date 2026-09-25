package ai.pdlc.core.platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publication-time validation for {@link AgentSpec} (configurable-agent-platform.md §2/§4). Pure:
 * the caller supplies the set of models the workspace is authorized to bind, so a model outside the
 * catalog blocks publication instead of silently falling back to a default at run time.
 *
 * <p>Templates use Mustache syntax with a restricted, data-only feature set: partials
 * ({@code {{> name}}}) and delimiter changes ({@code {{=<% %>=}}}) are rejected, and every
 * top-level variable or section referenced outside a section must be declared. Names inside a
 * section resolve against the section's item, so they are not checked here.
 */
public final class AgentSpecValidator {

    public static final int MAX_OUTPUT_TOKENS = 200_000;
    public static final int MAX_TIMEOUT_SECONDS = 3_600;
    public static final int MAX_MODEL_TURNS = 32;
    public static final int MAX_TOOL_CALLS = 64;

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final Pattern CONNECTION_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    private static final Pattern SKILL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");
    /** A relative path, optionally with a query; no scheme, no authority, no dot segments. */
    private static final Pattern REST_PATH = Pattern.compile("^/(?!/)[A-Za-z0-9._~!$&'()*+,;=:@%/{}?-]{0,511}$");
    private static final Pattern POINTER = Pattern.compile("^(/([^~/]|~[01])*)*$");
    static final java.util.Set<String> REST_STATES = java.util.Set.of("WORKING", "COMPLETED", "FAILED", "CANCELED");
    public static final int MAX_POLL_SECONDS = 60;
    private static final Pattern TAG = Pattern.compile("\\{\\{(\\{?)\\s*([#^/&>=!]?)\\s*([^}]*?)\\s*}?}}");

    private AgentSpecValidator() {
    }

    /** Returns every problem found, in a stable order; an empty list means publishable. */
    public static List<String> validate(String name, AgentSpec spec, Set<String> authorizedModels) {
        List<String> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add("name is required");
        }
        if (spec == null) {
            errors.add("spec is required");
            return errors;
        }
        boolean a2a = spec.isA2a();
        boolean rest = spec.usesRestRuntime();
        boolean remoteRuntime = a2a || rest;
        if (!remoteRuntime && !AgentSpec.RUNTIME_NATIVE.equals(spec.runtime())) {
            errors.add("runtime must be \"" + AgentSpec.RUNTIME_NATIVE + "\", \"" + AgentSpec.RUNTIME_A2A + "\" or \""
                    + AgentSpec.RUNTIME_REST + "\"");
        }
        if (rest) {
            checkRest(spec, errors);
        } else if (spec.rest() != null) {
            errors.add("rest is only for runtime \"" + AgentSpec.RUNTIME_REST + "\"");
        }
        if (a2a) {
            AgentSpec.Remote remote = spec.remote();
            if (remote == null || remote.connectionId() == null || !CONNECTION_ID.matcher(remote.connectionId()).matches()) {
                errors.add("remote.connectionId must name an A2A_AGENT connection");
            }
            if (remote == null || remote.skill() == null || !SKILL.matcher(remote.skill()).matches()) {
                errors.add("remote.skill must be a skill id from the remote agent's card");
            }
            if (spec.model() != null) {
                errors.add("an a2a agent has no model binding; the remote agent chooses its own");
            }
            if (!spec.toolsOrEmpty().isEmpty()) {
                errors.add("an a2a agent has no tools; the remote agent uses its own");
            }
        } else if (spec.remote() != null) {
            errors.add("remote is only for runtime \"" + AgentSpec.RUNTIME_A2A + "\"");
        }

        Set<String> declared = new HashSet<>();
        if (spec.variables() != null) {
            for (AgentSpec.Variable v : spec.variables()) {
                if (v == null || v.name() == null || !NAME.matcher(v.name()).matches()) {
                    errors.add("variable name must match " + NAME.pattern());
                } else if (!declared.add(v.name())) {
                    errors.add("duplicate variable \"" + v.name() + "\"");
                }
            }
        }

        if (spec.prompt() == null || spec.prompt().isBlank()) {
            if (!rest) {
                errors.add("prompt is required");
            }
        } else {
            checkTemplate(spec.prompt(), declared, errors);
        }

        if (!remoteRuntime) {
            checkModel(spec.model(), authorizedModels, errors);
        }

        AgentSpec.Limits limits = spec.limits();
        if (limits == null || limits.timeoutSeconds() == null) {
            errors.add("limits.timeoutSeconds is required");
        } else {
            if (limits.timeoutSeconds() < 1 || limits.timeoutSeconds() > MAX_TIMEOUT_SECONDS) {
                errors.add("limits.timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
            }
            if (limits.maxOutputTokens() != null
                    && (limits.maxOutputTokens() < 1 || limits.maxOutputTokens() > MAX_OUTPUT_TOKENS)) {
                errors.add("limits.maxOutputTokens must be between 1 and " + MAX_OUTPUT_TOKENS);
            }
            if (limits.maxModelTurns() != null && (limits.maxModelTurns() < 1 || limits.maxModelTurns() > MAX_MODEL_TURNS)) {
                errors.add("limits.maxModelTurns must be between 1 and " + MAX_MODEL_TURNS);
            }
            if (limits.maxToolCalls() != null && (limits.maxToolCalls() < 1 || limits.maxToolCalls() > MAX_TOOL_CALLS)) {
                errors.add("limits.maxToolCalls must be between 1 and " + MAX_TOOL_CALLS);
            }
        }
        Set<String> tools = new HashSet<>();
        for (AgentSpec.ToolRef ref : spec.toolsOrEmpty()) {
            if (ref == null || ref.tool() == null || ref.tool().isBlank()) {
                errors.add("tools[].tool is required");
            } else if (ref.version() == null || ref.version() < 1) {
                errors.add("tool \"" + ref.tool() + "\" must pin a published version");
            } else if (!tools.add(ref.tool())) {
                errors.add("tool \"" + ref.tool() + "\" is listed more than once");
            }
        }
        if (spec.outputSchema() != null) {
            errors.addAll(OutputSchema.unsupported(spec.outputSchema()));
        }
        return errors;
    }

    /** A {@code rest} agent: a complete, explicit mapping - nothing about the remote job is inferred. */
    private static void checkRest(AgentSpec spec, List<String> errors) {
        if (spec.model() != null) {
            errors.add("a rest agent has no model binding; the service chooses its own");
        }
        if (!spec.toolsOrEmpty().isEmpty()) {
            errors.add("a rest agent has no tools; the service uses its own");
        }
        AgentSpec.RestBinding r = spec.rest();
        if (r == null) {
            errors.add("rest is required for runtime \"rest\"");
            return;
        }
        if (r.connectionId() == null || !CONNECTION_ID.matcher(r.connectionId()).matches()) {
            errors.add("rest.connectionId must name a REST_AGENT connection");
        }
        boolean async = AgentSpec.RestBinding.ASYNC.equals(r.mode());
        if (!async && !AgentSpec.RestBinding.SYNC.equals(r.mode())) {
            errors.add("rest.mode must be \"sync\" or \"async\"");
        }
        checkEndpoint("rest.submit", r.submit(), Set.of("POST", "PUT"), false, true, errors);
        pointer("rest.resultPointer", r.resultPointer(), false, errors);
        pointer("rest.errorPointer", r.errorPointer(), false, errors);
        if (r.idempotency() != null && !Set.of("HEADER", "NONE").contains(r.idempotency())) {
            errors.add("rest.idempotency must be HEADER or NONE");
        }
        if (!async) {
            if (r.status() != null || r.cancel() != null || r.taskIdPointer() != null || r.statePointer() != null
                    || r.states() != null || r.pollSeconds() != null) {
                errors.add("a sync rest agent has no status, cancel, taskIdPointer, statePointer, states or pollSeconds");
            }
            return;
        }
        checkEndpoint("rest.status", r.status(), Set.of("GET"), true, true, errors);
        checkEndpoint("rest.cancel", r.cancel(), Set.of("POST", "DELETE"), true, false, errors);
        pointer("rest.taskIdPointer", r.taskIdPointer(), true, errors);
        pointer("rest.statePointer", r.statePointer(), true, errors);
        if (r.states() == null || r.states().isEmpty()) {
            errors.add("rest.states must map the service's state values to WORKING, COMPLETED, FAILED or CANCELED");
        } else {
            r.states().forEach((remote, mapped) -> {
                if (remote == null || remote.isEmpty() || !REST_STATES.contains(mapped)) {
                    errors.add("rest.states[" + remote + "] must be one of " + new java.util.TreeSet<>(REST_STATES));
                }
            });
            if (!r.states().containsValue("COMPLETED") || !r.states().containsValue("FAILED")) {
                errors.add("rest.states must name at least one COMPLETED and one FAILED value");
            }
        }
        if (r.pollSeconds() != null && (r.pollSeconds() < 1 || r.pollSeconds() > MAX_POLL_SECONDS)) {
            errors.add("rest.pollSeconds must be between 1 and " + MAX_POLL_SECONDS);
        }
    }

    private static void checkEndpoint(String field, AgentSpec.Endpoint e, Set<String> methods, boolean needsTaskId, boolean required,
                                      List<String> errors) {
        if (e == null) {
            if (required) {
                errors.add(field + " is required");
            }
            return;
        }
        if (e.method() == null || !methods.contains(e.method())) {
            errors.add(field + ".method must be one of " + new java.util.TreeSet<>(methods));
        }
        if (e.path() == null || !REST_PATH.matcher(e.path()).matches() || e.path().contains("/../") || e.path().endsWith("/..")
                || e.path().contains("/./")) {
            errors.add(field + ".path must be a relative path starting with /");
        } else if (needsTaskId && !e.path().contains("{taskId}")) {
            errors.add(field + ".path must contain {taskId}");
        } else if (!needsTaskId && e.path().contains("{")) {
            errors.add(field + ".path has no placeholders");
        }
    }

    private static void pointer(String field, String value, boolean required, List<String> errors) {
        if (value == null) {
            if (required) {
                errors.add(field + " is required");
            }
        } else if (!POINTER.matcher(value).matches()) {
            errors.add(field + " must be a JSON Pointer such as /job/id");
        }
    }

    private static void checkModel(AgentSpec.ModelBinding model, Set<String> authorizedModels, List<String> errors) {
        if (model == null || model.model() == null || model.model().isBlank()) {
            errors.add("model.model is required");
            return;
        }
        if (!authorizedModels.contains(model.model())) {
            errors.add("model \"" + model.model() + "\" is not in the authorized model catalog");
        }
        Set<String> seen = new HashSet<>(Set.of(model.model()));
        if (model.fallbacks() != null) {
            for (String fallback : model.fallbacks()) {
                if (fallback == null || !seen.add(fallback)) {
                    errors.add("model.fallbacks must be distinct from each other and from model.model");
                } else if (!authorizedModels.contains(fallback)) {
                    errors.add("fallback model \"" + fallback + "\" is not in the authorized model catalog");
                }
            }
        }
    }

    /**
     * The top-level variable names a prompt references outside sections - exactly the names
     * publication requires to be declared (names inside a section resolve against its item).
     */
    public static List<String> referencedVariables(String prompt) {
        Set<String> names = new LinkedHashSet<>();
        List<String> open = new ArrayList<>();
        Matcher m = TAG.matcher(prompt == null ? "" : prompt);
        while (m.find()) {
            String sigil = m.group(2);
            String key = m.group(3);
            switch (sigil) {
                case ">", "=", "!" -> { }
                case "#", "^" -> {
                    if (open.isEmpty()) {
                        requireDeclared(key, Set.of(), names);
                    }
                    open.add(key);
                }
                case "/" -> {
                    if (!open.isEmpty()) {
                        open.remove(open.size() - 1);
                    }
                }
                default -> {
                    if (open.isEmpty()) {
                        requireDeclared(key, Set.of(), names);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    private static void checkTemplate(String prompt, Set<String> declared, List<String> errors) {
        Matcher m = TAG.matcher(prompt);
        List<String> open = new ArrayList<>();
        Set<String> undeclared = new LinkedHashSet<>();
        while (m.find()) {
            String sigil = m.group(2);
            String key = m.group(3);
            switch (sigil) {
                case ">" -> errors.add("prompt must not use partials ({{> " + key + "}})");
                case "=" -> errors.add("prompt must not change Mustache delimiters");
                case "!" -> { /* comment */ }
                case "#", "^" -> {
                    if (open.isEmpty()) {
                        requireDeclared(key, declared, undeclared);
                    }
                    open.add(key);
                }
                case "/" -> {
                    if (open.isEmpty() || !open.get(open.size() - 1).equals(key)) {
                        errors.add("prompt has an unbalanced section close {{/" + key + "}}");
                    } else {
                        open.remove(open.size() - 1);
                    }
                }
                default -> {
                    if (open.isEmpty()) {
                        requireDeclared(key, declared, undeclared);
                    }
                }
            }
        }
        if (!open.isEmpty()) {
            errors.add("prompt has an unclosed section {{#" + open.get(open.size() - 1) + "}}");
        }
        for (String name : undeclared) {
            errors.add("prompt references undeclared variable \"" + name + "\"");
        }
    }

    private static void requireDeclared(String key, Set<String> declared, Set<String> undeclared) {
        if (key.equals(".")) {
            return;
        }
        String top = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        if (!declared.contains(top)) {
            undeclared.add(top);
        }
    }
}
