package ai.pdlc.core.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Model-supplied tool arguments: parsed strictly, validated against the tool's input schema (the
 * {@link OutputSchema} subset, plus "no undeclared properties"), and identified by a canonical hash
 * (key order independent) for the call trace and - from slice 2.2 - approvals.
 */
public final class ToolArgs {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Parsed(JsonNode args, List<String> errors, String hash) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private ToolArgs() {
    }

    public static Parsed parse(String argumentsJson, Map<String, Object> inputSchema) {
        JsonNode args;
        try {
            args = JSON.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception e) {
            return new Parsed(null, List.of("arguments are not valid JSON"), null);
        }
        List<String> errors = new ArrayList<>(OutputSchema.validate(inputSchema, args));
        if (args.isObject() && inputSchema.get("properties") instanceof Map<?, ?> props) {
            args.fieldNames().forEachRemaining(f -> {
                if (!props.containsKey(f)) {
                    errors.add("$." + f + " is not a declared argument");
                }
            });
        }
        return new Parsed(args, errors, ContentHash.of(JSON.convertValue(args, Object.class)));
    }
}
