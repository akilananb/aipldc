package ai.pdlc.agents.po;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic INVEST validation over a parsed story (playbook §2 "Validates — INVEST"). Pure Java,
 * no LLM: each letter is a concrete check over {@link StoryParser}'s extraction of the story markdown.
 * Results are {@code "pass"}/{@code "fail"} keyed by letter I/N/V/E/S/T, fed straight into
 * {@link ai.pdlc.core.domain.PoHandoff#invest()}.
 */
public final class InvestValidator {

    /** Banned vague THEN phrases (playbook T row). */
    static final List<String> VAGUE_THEN = List.of("works well", "is fast", "user-friendly", "good enough", "nice to have");

    /** Observable-result markers: an HTTP status, a DOM element, a log row, a DB row, a file, a metric. */
    static final List<String> OBSERVABLE_MARKERS = List.of(
            "http", "status", "row", "log", "error", "file", "download", "message", "response",
            "request", "written", "created", "rendered", "metric", "column");

    /** Implementation nouns that violate N when present outside the NFR section (class names, libraries). */
    static final List<String> IMPLEMENTATION_NOUNS = List.of(
            "class ", "interface ", "method ", "abstract ", "singleton",
            "spring", "lombok", "react", "angular", "vue", "hibernate", "mybatis", "jackson", "gson",
            "express", "django", "ktor", "akka", "redux", "tailwind", "bootstrap");

    /** Capitalized CamelCase token (e.g. {@code CsvExporter}), a proxy for a class/type name. */
    private static final Pattern CLASS_LIKE = Pattern.compile("\\b[A-Z][a-z]+[A-Z][a-zA-Z0-9]*\\b");

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private InvestValidator() {
    }

    public static Map<String, String> validate(String storyMarkdown) {
        return StoryParser.result(Map.of(
                'I', independent(storyMarkdown),
                'N', negotiable(storyMarkdown),
                'V', valuable(storyMarkdown),
                'E', estimable(storyMarkdown),
                'S', small(storyMarkdown),
                'T', testable(storyMarkdown)));
    }

    /** I — Dependencies lists no other open story; internal tasks ("(same story", "task T") and "none" are fine. */
    public static boolean independent(String markdown) {
        List<String> deps = StoryParser.sectionBullets(markdown, "Dependencies");
        if (deps.isEmpty()) {
            return true; // no dependencies at all
        }
        for (String dep : deps) {
            String lower = dep.toLowerCase();
            if (lower.startsWith("none")) {
                continue;
            }
            if (lower.contains("(same story") || lower.contains("task t")) {
                continue; // internal task within this story
            }
            return false; // an external / open story dependency
        }
        return true;
    }

    /** N — no implementation nouns (class names, library choices) outside the NFR section. */
    public static boolean negotiable(String markdown) {
        String body = StoryParser.withoutSections(markdown, "NFR");
        for (String noun : IMPLEMENTATION_NOUNS) {
            if (body.toLowerCase().contains(noun)) {
                return false;
            }
        }
        return !CLASS_LIKE.matcher(body).find();
    }

    /** V — the "So that" line is present and non-empty. */
    public static boolean valuable(String markdown) {
        return StoryParser.soThat(markdown) != null;
    }

    /** E — every scenario names concrete data: a number, a role, or a field token. */
    public static boolean estimable(String markdown) {
        List<StoryParser.Scenario> scenarios = StoryParser.scenarios(markdown);
        if (scenarios.isEmpty()) {
            return false;
        }
        for (StoryParser.Scenario s : scenarios) {
            String text = String.join(" ", s.given()) + " " + String.join(" ", s.when()) + " " + String.join(" ", s.then());
            if (!NUMBER.matcher(text).find() && !hasRoleOrField(text)) {
                return false;
            }
        }
        return true;
    }

    /** S — at most six scenarios. */
    public static boolean small(String markdown) {
        return StoryParser.scenarios(markdown).size() <= 6;
    }

    /** T — every THEN is observable; no vague phrases; a number or an observable marker is present. */
    public static boolean testable(String markdown) {
        List<String> thens = StoryParser.thenLines(markdown);
        if (thens.isEmpty()) {
            return false;
        }
        for (String then : thens) {
            String lower = then.toLowerCase();
            for (String vague : VAGUE_THEN) {
                if (lower.contains(vague)) {
                    return false;
                }
            }
            if (!NUMBER.matcher(then).find() && !hasObservable(then)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasObservable(String then) {
        String lower = then.toLowerCase();
        for (String marker : OBSERVABLE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> ROLE_OR_FIELD_TOKENS = Set.of(
            "admin", "user", "customer", "role", "permission", "tenant", "field", "column", "email", "name",
            "price", "quantity", "filter", "status", "id", "date");

    private static boolean hasRoleOrField(String text) {
        String lower = text.toLowerCase();
        for (String token : ROLE_OR_FIELD_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
