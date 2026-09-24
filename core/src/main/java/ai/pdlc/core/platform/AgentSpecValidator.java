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

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
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
        if (!AgentSpec.RUNTIME_NATIVE.equals(spec.runtime())) {
            errors.add("runtime must be \"" + AgentSpec.RUNTIME_NATIVE + "\"");
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
            errors.add("prompt is required");
        } else {
            checkTemplate(spec.prompt(), declared, errors);
        }

        AgentSpec.ModelBinding model = spec.model();
        if (model == null || model.model() == null || model.model().isBlank()) {
            errors.add("model.model is required");
        } else {
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
        }
        if (spec.outputSchema() != null) {
            errors.addAll(OutputSchema.unsupported(spec.outputSchema()));
        }
        return errors;
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
