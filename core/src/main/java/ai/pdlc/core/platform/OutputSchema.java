package ai.pdlc.core.platform;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The JSON Schema subset an agent's {@code outputSchema} and a tool's {@code inputSchema} may use,
 * enforced exactly: {@code type} ({@code object|array|string|number|integer|boolean|null}),
 * {@code properties}, {@code required}, {@code items}, {@code enum}, {@code additionalProperties},
 * and the constraints {@code minimum}/{@code maximum}, {@code minLength}/{@code maxLength},
 * {@code minItems}/{@code maxItems} and {@code pattern}. The annotations {@code description},
 * {@code title}, {@code default}, {@code examples}, {@code format} and {@code $schema} carry no
 * constraint and are accepted as documentation (slice 2.3: real MCP schemas use them). Any other
 * keyword is rejected at publication ({@link #unsupported}) rather than silently ignored at run
 * time, so a published schema always means what it says.
 */
public final class OutputSchema {

    static final Set<String> KEYWORDS = new TreeSet<>(Set.of("type", "properties", "required", "items", "enum",
            "additionalProperties", "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "pattern",
            "description", "title", "default", "examples", "format", "$schema"));
    static final Set<String> NUMERIC = Set.of("minimum", "maximum");
    static final Set<String> COUNTS = Set.of("minLength", "maxLength", "minItems", "maxItems");
    /** Patterns run against model-supplied values; a length cap keeps them reviewable and cheap. */
    static final int MAX_PATTERN_LENGTH = 200;
    static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean", "null");

    private OutputSchema() {
    }

    /** Publication check: problems with the schema itself. */
    public static List<String> unsupported(Map<String, Object> schema) {
        List<String> errors = new ArrayList<>();
        checkSchema(schema, "outputSchema", errors);
        return errors;
    }

    /** Run-time check: how {@code value} violates {@code schema}; empty when it conforms. */
    public static List<String> validate(Map<String, Object> schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        validate(schema, value, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static void checkSchema(Object node, String path, List<String> errors) {
        if (!(node instanceof Map<?, ?> map)) {
            errors.add(path + " must be an object");
            return;
        }
        for (Object key : map.keySet()) {
            if (!KEYWORDS.contains(String.valueOf(key))) {
                errors.add(path + " uses unsupported keyword \"" + key + "\" (supported: " + KEYWORDS + ")");
            }
        }
        Object type = map.get("type");
        if (type != null && !TYPES.contains(String.valueOf(type))) {
            errors.add(path + ".type must be one of " + TYPES);
        }
        if (map.get("properties") instanceof Map<?, ?> props) {
            props.forEach((name, sub) -> checkSchema(sub, path + ".properties." + name, errors));
        } else if (map.containsKey("properties")) {
            errors.add(path + ".properties must be an object");
        }
        if (map.containsKey("required") && !(map.get("required") instanceof List<?>)) {
            errors.add(path + ".required must be an array");
        }
        if (map.containsKey("items")) {
            checkSchema(map.get("items"), path + ".items", errors);
        }
        if (map.containsKey("enum") && !(map.get("enum") instanceof List<?>)) {
            errors.add(path + ".enum must be an array");
        }
        Object additional = map.get("additionalProperties");
        if (additional instanceof Map<?, ?>) {
            checkSchema(additional, path + ".additionalProperties", errors);
        } else if (additional != null && !(additional instanceof Boolean)) {
            errors.add(path + ".additionalProperties must be a boolean or a schema");
        }
        for (String k : NUMERIC) {
            if (map.containsKey(k) && !(map.get(k) instanceof Number)) {
                errors.add(path + "." + k + " must be a number");
            }
        }
        for (String k : COUNTS) {
            if (map.containsKey(k) && !(map.get(k) instanceof Number n && n.longValue() >= 0 && n.doubleValue() == n.longValue())) {
                errors.add(path + "." + k + " must be a non-negative integer");
            }
        }
        if (map.containsKey("pattern")) {
            if (!(map.get("pattern") instanceof String p) || p.length() > MAX_PATTERN_LENGTH) {
                errors.add(path + ".pattern must be a string of at most " + MAX_PATTERN_LENGTH + " characters");
            } else {
                try {
                    Pattern.compile(p);
                } catch (PatternSyntaxException e) {
                    errors.add(path + ".pattern is not a valid regular expression");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void validate(Map<String, Object> schema, JsonNode value, String path, List<String> errors) {
        Object type = schema.get("type");
        if (type != null && !matchesType(String.valueOf(type), value)) {
            errors.add(path + " must be " + type);
            return;
        }
        if (schema.get("enum") instanceof List<?> allowed
                && allowed.stream().noneMatch(a -> String.valueOf(a).equals(value.isTextual() ? value.asText() : value.toString()))) {
            errors.add(path + " must be one of " + allowed);
        }
        if (value.isObject()) {
            if (schema.get("required") instanceof List<?> required) {
                for (Object name : required) {
                    if (!value.has(String.valueOf(name))) {
                        errors.add(path + "." + name + " is required");
                    }
                }
            }
            if (schema.get("properties") instanceof Map<?, ?> props) {
                props.forEach((name, sub) -> {
                    JsonNode child = value.get(String.valueOf(name));
                    if (child != null && sub instanceof Map<?, ?> subSchema) {
                        validate((Map<String, Object>) subSchema, child, path + "." + name, errors);
                    }
                });
            }
        }
        if (value.isArray() && schema.get("items") instanceof Map<?, ?> items) {
            for (int i = 0; i < value.size(); i++) {
                validate((Map<String, Object>) items, value.get(i), path + "[" + i + "]", errors);
            }
        }
        if (value.isObject()) {
            Map<?, ?> props = schema.get("properties") instanceof Map<?, ?> p ? p : Map.of();
            Object additional = schema.get("additionalProperties");
            value.fieldNames().forEachRemaining(name -> {
                if (props.containsKey(name)) {
                    return;
                }
                if (Boolean.FALSE.equals(additional)) {
                    errors.add(path + "." + name + " is not allowed");
                } else if (additional instanceof Map<?, ?> extra) {
                    validate((Map<String, Object>) extra, value.get(name), path + "." + name, errors);
                }
            });
        }
        if (value.isNumber()) {
            if (schema.get("minimum") instanceof Number min && value.doubleValue() < min.doubleValue()) {
                errors.add(path + " must be >= " + min);
            }
            if (schema.get("maximum") instanceof Number max && value.doubleValue() > max.doubleValue()) {
                errors.add(path + " must be <= " + max);
            }
        }
        if (value.isTextual()) {
            int length = value.asText().codePointCount(0, value.asText().length());
            if (schema.get("minLength") instanceof Number min && length < min.intValue()) {
                errors.add(path + " must be at least " + min + " characters");
            }
            if (schema.get("maxLength") instanceof Number max && length > max.intValue()) {
                errors.add(path + " must be at most " + max + " characters");
            }
            if (schema.get("pattern") instanceof String p && !Pattern.compile(p).matcher(value.asText()).find()) {
                errors.add(path + " must match " + p);
            }
        }
        if (value.isArray()) {
            if (schema.get("minItems") instanceof Number min && value.size() < min.intValue()) {
                errors.add(path + " must have at least " + min + " items");
            }
            if (schema.get("maxItems") instanceof Number max && value.size() > max.intValue()) {
                errors.add(path + " must have at most " + max + " items");
            }
        }
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }
}
