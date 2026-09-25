package ai.pdlc.core.platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run-time input check for a published {@link AgentSpec} (configurable-agent-platform.md §4:
 * "validate required variables before invocation"). Run start and the runner both apply it: every
 * required variable must be present and non-blank, and no input may name an undeclared variable -
 * so a caller cannot smuggle extra data into the template's view.
 */
public final class AgentInputs {

    private AgentInputs() {
    }

    public static List<String> validate(AgentSpec spec, Map<String, String> inputs) {
        Map<String, String> given = inputs == null ? Map.of() : inputs;
        List<String> errors = new ArrayList<>();
        Set<String> declared = new HashSet<>();
        if (spec.variables() != null) {
            for (AgentSpec.Variable v : spec.variables()) {
                declared.add(v.name());
                String value = given.get(v.name());
                if (v.required() && (value == null || value.isBlank())) {
                    errors.add("input \"" + v.name() + "\" is required");
                }
            }
        }
        given.keySet().stream().filter(k -> !declared.contains(k)).sorted()
                .forEach(k -> errors.add("input \"" + k + "\" is not a declared variable"));
        return errors;
    }
}
