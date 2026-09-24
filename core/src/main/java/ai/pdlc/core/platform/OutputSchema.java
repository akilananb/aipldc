package ai.pdlc.core.platform;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The JSON Schema subset an agent's {@code outputSchema} may use, enforced exactly: {@code type}
 * ({@code object|array|string|number|integer|boolean|null}), {@code properties}, {@code required},
 * {@code items}, {@code enum}, {@code description}. Any other keyword is rejected at publication
 * ({@link #unsupported}) rather than silently ignored at run time, so a published schema always
 * means what it says.
 */
public final class OutputSchema {

    static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "items", "enum", "description");
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
