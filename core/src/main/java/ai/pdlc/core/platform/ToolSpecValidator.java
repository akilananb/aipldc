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
    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final Pattern PATH_PARAM = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");
    /** Tool names the model sees: OpenAI function-name rules. */
    public static final Pattern TOOL_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

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
        if (!ToolSpec.KIND_HTTP.equals(spec.kind())) {
            errors.add("kind must be \"http\"");
        }
        if (spec.connectionId() == null || spec.connectionId().isBlank()) {
            errors.add("connectionId is required");
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
        if (spec.timeoutSeconds() == null || spec.timeoutSeconds() < 1 || spec.timeoutSeconds() > MAX_TIMEOUT_SECONDS) {
            errors.add("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }
        if (spec.maxResponseBytes() == null || spec.maxResponseBytes() < 256 || spec.maxResponseBytes() > MAX_RESPONSE_BYTES) {
            errors.add("maxResponseBytes must be between 256 and " + MAX_RESPONSE_BYTES);
        }
        return errors;
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
