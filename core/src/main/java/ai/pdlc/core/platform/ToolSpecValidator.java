package ai.pdlc.core.platform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Publication checks for {@link ToolSpec} (structure only; the registry checks the connection and grant). */
public final class ToolSpecValidator {

    public static final int MAX_TIMEOUT_SECONDS = 120;
    public static final int MAX_RESPONSE_BYTES = 1_000_000;
    /** Approval waits are bounded: a pending write can hold a run at most 30 days. */
    public static final int MAX_APPROVAL_MINUTES = 43_200;
    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final Pattern PATH_PARAM = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");
    /** Tool names the model sees: OpenAI function-name rules. */
    public static final Pattern TOOL_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    /** MCP tool names as servers publish them. */
    public static final Pattern MCP_TOOL_NAME = Pattern.compile("^[A-Za-z0-9_./-]{1,128}$");

    private ToolSpecValidator() {
    }

    public static List<String> validate(String name, ToolSpec spec) {
        List<String> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add("name is required");
        }
        if (spec == null) {
            errors.add("spec is required");
            return errors;
        }
        if (spec.description() == null || spec.description().isBlank()) {
            errors.add("description is required (it is what the model is told)");
        }
        if (spec.connectionId() == null || spec.connectionId().isBlank()) {
            errors.add("connectionId is required");
        }
        if (ToolSpec.KIND_MCP.equals(spec.kind())) {
            validateMcp(spec, errors);
            return errors;
        }
        if (!ToolSpec.KIND_HTTP.equals(spec.kind())) {
            errors.add("kind must be \"http\" or \"mcp\"");
        }
        if (spec.mcpTool() != null || spec.mcpFingerprint() != null) {
            errors.add("mcpTool and mcpFingerprint apply only to mcp tools");
        }
        if (spec.method() == null || !METHODS.contains(spec.method())) {
            errors.add("method must be one of " + METHODS);
        }
        if (!ToolSpec.READ.equals(spec.effect()) && !ToolSpec.WRITE.equals(spec.effect())) {
            errors.add("effect must be READ or WRITE");
        } else if (spec.method() != null) {
            if ("GET".equals(spec.method()) && ToolSpec.WRITE.equals(spec.effect())) {
                errors.add("a GET tool must have effect READ");
            }
            if (!"GET".equals(spec.method()) && ToolSpec.READ.equals(spec.effect())) {
                errors.add(spec.method() + " tools must have effect WRITE (only GET is treated as read-only)");
            }
        }
        Set<String> declared = declaredProperties(spec.inputSchema(), errors);
        if (spec.path() == null || !spec.path().startsWith("/")) {
            errors.add("path must start with /");
        } else if (spec.path().contains("..") || spec.path().contains("//") || spec.path().contains("\\")
                || spec.path().chars().anyMatch(Character::isWhitespace) || spec.path().contains("?") || spec.path().contains("#")) {
            errors.add("path must be a plain path template (no .., //, backslash, whitespace, query or fragment)");
        } else {
            for (String param : pathParams(spec.path())) {
                if (!declared.contains(param)) {
                    errors.add("path parameter {" + param + "} is not declared in inputSchema.properties");
                }
            }
        }
        checkLimitsAndApproval(spec, errors);
        return errors;
    }

    /** An MCP tool: the server defines the call; the review pins its name, schema and fingerprint. */
    private static void validateMcp(ToolSpec spec, List<String> errors) {
        if (spec.mcpTool() == null || !MCP_TOOL_NAME.matcher(spec.mcpTool()).matches()) {
            errors.add("mcpTool must match " + MCP_TOOL_NAME.pattern());
        }
        if (spec.mcpFingerprint() == null || !spec.mcpFingerprint().startsWith("sha256:")) {
            errors.add("mcpFingerprint is required (from discovery)");
        }
        if (spec.method() != null || spec.path() != null) {
            errors.add("mcp tools have no method or path");
        }
        if (!ToolSpec.READ.equals(spec.effect()) && !ToolSpec.WRITE.equals(spec.effect())) {
            errors.add("effect must be READ or WRITE");
        }
        if (spec.idempotency() != null && !ToolSpec.IDEMPOTENCY_NONE.equals(spec.idempotency())) {
            errors.add("mcp tools have no idempotency key support; idempotency must be NONE");
        }
        declaredProperties(spec.inputSchema(), errors);
        checkLimitsAndApproval(spec, errors);
    }

    private static void checkLimitsAndApproval(ToolSpec spec, List<String> errors) {
        if (spec.timeoutSeconds() == null || spec.timeoutSeconds() < 1 || spec.timeoutSeconds() > MAX_TIMEOUT_SECONDS) {
            errors.add("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }
        if (spec.maxResponseBytes() == null || spec.maxResponseBytes() < 256 || spec.maxResponseBytes() > MAX_RESPONSE_BYTES) {
            errors.add("maxResponseBytes must be between 256 and " + MAX_RESPONSE_BYTES);
        }
        if (spec.idempotency() != null && !ToolSpec.IDEMPOTENCY_HEADER.equals(spec.idempotency())
                && !ToolSpec.IDEMPOTENCY_NONE.equals(spec.idempotency())) {
            errors.add("idempotency must be HEADER or NONE");
        }
        if (spec.approval() != null) {
            ToolSpec.Approval a = spec.approval();
            if (a.escalateAfter() < 1 || a.escalateAfter() > MAX_APPROVAL_MINUTES
                    || a.expireAfter() < 1 || a.expireAfter() > MAX_APPROVAL_MINUTES) {
                errors.add("approval minutes must be between 1 and " + MAX_APPROVAL_MINUTES);
            } else if (a.escalateAfter() > a.expireAfter()) {
                errors.add("approval.escalateAfterMinutes must not exceed approval.expireAfterMinutes");
            }
            if (ToolSpec.READ.equals(spec.effect())) {
                errors.add("approval settings apply only to WRITE tools");
            }
        }
    }

    public static Set<String> pathParams(String path) {
        Set<String> params = new LinkedHashSet<>();
        Matcher m = PATH_PARAM.matcher(path == null ? "" : path);
        while (m.find()) {
            params.add(m.group(1));
        }
        return params;
    }

    private static Set<String> declaredProperties(Map<String, Object> schema, List<String> errors) {
        if (schema == null) {
            errors.add("inputSchema is required (use {\"type\": \"object\", \"properties\": {}} for no arguments)");
            return Set.of();
        }
        errors.addAll(OutputSchema.unsupported(schema).stream().map(e -> e.replace("outputSchema", "inputSchema")).toList());
        if (!"object".equals(schema.get("type"))) {
            errors.add("inputSchema.type must be \"object\"");
        }
        Object props = schema.get("properties");
        return props instanceof Map<?, ?> map ? map.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()) : Set.of();
    }
}
