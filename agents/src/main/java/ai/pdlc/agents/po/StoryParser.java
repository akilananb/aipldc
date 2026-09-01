package ai.pdlc.agents.po;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic markdown parser for the PO story format (playbook lines 120-149). Extracts the
 * pieces INVEST/DoR and the spec-delta writer need, without any LLM. Used by {@link InvestValidator},
 * {@link DorValidator} and {@link PoAgent}; the story markdown itself is passed through unchanged
 * (line positions matter for the review UI's line anchors).
 */
public final class StoryParser {

    public record Scenario(String name, List<String> given, List<String> when, List<String> then) {
    }

    private static final Pattern HEADING = Pattern.compile("^##\\s+(.+)$");
    private static final Pattern SCENARIO = Pattern.compile("^\\s*Scenario:\\s*(.+)$");
    private static final Pattern KEYWORD_LINE = Pattern.compile("^\\s*(GIVEN|WHEN|THEN|AND)\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*-\\s+(.*)$");

    private StoryParser() {
    }

    public static List<Scenario> scenarios(String markdown) {
        List<Scenario> out = new ArrayList<>();
        String name = null;
        List<String> given = new ArrayList<>();
        List<String> when = new ArrayList<>();
        List<String> then = new ArrayList<>();
        for (String raw : lines(markdown)) {
            Matcher s = SCENARIO.matcher(raw);
            if (s.matches()) {
                if (name != null) {
                    out.add(new Scenario(name, given, when, then));
                }
                name = s.group(1).trim();
                given = new ArrayList<>();
                when = new ArrayList<>();
                then = new ArrayList<>();
                continue;
            }
            if (name == null) {
                continue;
            }
            Matcher k = KEYWORD_LINE.matcher(raw);
            if (k.matches()) {
                String keyword = k.group(1).toUpperCase();
                String text = k.group(2).trim();
                switch (keyword) {
                    case "GIVEN" -> given.add(text);
                    case "WHEN" -> when.add(text);
                    case "THEN" -> then.add(text);
                    case "AND" -> {
                        if (!then.isEmpty()) {
                            then.add(text);
                        } else if (!when.isEmpty()) {
                            when.add(text);
                        } else if (!given.isEmpty()) {
                            given.add(text);
                        }
                    }
                    default -> { /* unreachable */ }
                }
            }
        }
        if (name != null) {
            out.add(new Scenario(name, given, when, then));
        }
        return out;
    }

    /** All THEN/AND-after-THEN observable-result lines, in story order. */
    public static List<String> thenLines(String markdown) {
        return scenarios(markdown).stream().flatMap(s -> s.then().stream()).toList();
    }

    /** The content after {@code So that}, or {@code null} if absent. */
    public static String soThat(String markdown) {
        for (String raw : lines(markdown)) {
            String trimmed = raw.trim();
            if (trimmed.toLowerCase().startsWith("so that")) {
                String rest = trimmed.substring("so that".length()).trim();
                return rest.isEmpty() ? null : rest;
            }
        }
        return null;
    }

    /** The area name from the {@code Feature: … · Area: X} line, or {@code null}. */
    public static String area(String markdown) {
        Pattern areaPattern = Pattern.compile("Area:\\s*([^\\s·]+)");
        Matcher m = areaPattern.matcher(markdown);
        return m.find() ? m.group(1) : null;
    }

    /** Bullet items under a {@code ## <heading>} section. */
    public static List<String> sectionBullets(String markdown, String heading) {
        List<String> bullets = new ArrayList<>();
        for (String line : sectionText(markdown, heading)) {
            Matcher b = BULLET.matcher(line);
            if (b.matches()) {
                bullets.add(b.group(1).trim());
            }
        }
        return bullets;
    }

    /** Non-empty content lines under a {@code ## <heading>} section, until the next {@code ## } heading. */
    public static List<String> sectionText(String markdown, String heading) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String raw : lines(markdown)) {
            Matcher h = HEADING.matcher(raw.trim());
            if (h.matches()) {
                in = h.group(1).trim().equalsIgnoreCase(heading);
                continue;
            }
            if (in && !raw.isBlank()) {
                out.add(raw);
            }
        }
        return out;
    }

    /** The whole markdown minus the named {@code ## } sections (used by the N check to exclude NFR). */
    public static String withoutSections(String markdown, String... headings) {
        StringBuilder out = new StringBuilder();
        boolean skip = false;
        for (String raw : lines(markdown)) {
            Matcher h = HEADING.matcher(raw.trim());
            if (h.matches()) {
                String name = h.group(1).trim();
                skip = false;
                for (String heading : headings) {
                    if (name.equalsIgnoreCase(heading)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    continue;
                }
            }
            if (!skip) {
                out.append(raw).append('\n');
            }
        }
        return out.toString();
    }

    public static String title(String markdown) {
        for (String raw : lines(markdown)) {
            String t = raw.trim();
            if (t.startsWith("# ")) {
                return t.substring(2).trim();
            }
        }
        return null;
    }

    /** First scenario name, used as a fallback slug component. */
    public static String firstScenarioName(String markdown) {
        List<Scenario> s = scenarios(markdown);
        return s.isEmpty() ? null : s.getFirst().name();
    }

    /** Split into lines, preserving empties so line numbers stay 1:1 with the source. */
    public static List<String> lines(String markdown) {
        return markdown == null ? List.of() : markdown.lines().toList();
    }

    /** Group {@code letter -> pass|fail} convenience for a result map. */
    public static Map<String, String> result(Map<Character, Boolean> checks) {
        Map<String, String> out = new LinkedHashMap<>();
        checks.forEach((letter, ok) -> out.put(String.valueOf(letter), ok ? "pass" : "fail"));
        return out;
    }
}
