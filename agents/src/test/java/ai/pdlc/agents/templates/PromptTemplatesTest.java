package ai.pdlc.agents.templates;

import ai.pdlc.core.config.PdlcConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-render tests for the bundled default templates (plan "Externalize hardcoded prompts &
 * release-doc templates"): each expected string is the exact pre-change hardcoded-assembly output
 * for a fixed fixture view (transcribed from {@code git show ab4e8f2} sources), so any drift in a
 * bundled {@code .mustache} file is caught here rather than by a real LLM call downstream.
 */
class PromptTemplatesTest {

    private static final PromptTemplates DEFAULTS = new PromptTemplates((String) null);

    /** Verbatim from {@code PoAgent.SCENARIO_FORMAT_SPEC} (the evaluated text-block value, `\`
     * line-continuations joined) — shared by the po-draft golden fixtures below. */
    private static final String SCENARIO_FORMAT_SPEC = """
            Use this EXACT format for acceptance criteria (required for machine parsing - do not \
            use bullets or bold text for the Given/When/Then lines):

            ## Acceptance criteria
            Scenario: <short-kebab-or-words-scenario-name>
              GIVEN <precondition>
              WHEN <action>
              THEN <observable result>

            Scenario: <another-scenario-name>
              GIVEN <precondition>
              WHEN <action>
              THEN <observable result>

            One scenario per acceptance-criteria bullet in the request; every "Scenario:" line and \
            every GIVEN/WHEN/THEN line must start at the beginning of the line (only leading \
            whitespace before the keyword).
            """;

    /** Verbatim from {@code po-draft.mustache} line 4 - the title-heading instruction. */
    private static final String TITLE_INSTRUCTION =
            "When your reply is a story (not a \"===QUESTIONS===\" follow-up below), its first line "
            + "must be a single \"# <short descriptive title for this story>\" heading (a concise "
            + "title for this specific story, not necessarily the literal feature title), followed "
            + "by a blank line, before any other content.\n";

    /** Verbatim from {@code po-draft.mustache} lines 18-20 — the multi-story actor/factor split
     * instruction appended after {@link #SCENARIO_FORMAT_SPEC}. */
    private static final String MULTI_STORY_SPEC = """
            If the feature involves MULTIPLE distinct actors/user types (e.g. admin vs customer) or clearly separable factors, write one complete story PER actor/factor, each following the full format above, separated by a line containing exactly:
            ===STORY===
            Write a single story (no separator) when one actor covers the whole feature.
            """;

    @Test
    void grillQuestionsRendersByteExactAndStartsWithMarker() {
        String expected = "[agent:grill]" + "\n"
                + "Generate grill intake questions (six fixed categories) for this feature.\n"
                + "Title: Export orders CSV\n"
                + "Description: Let SquadLead export orders as PII-safe CSV.\n"
                + "Respond with JSON only: {\"type_decision\":\"story\",\"questions\":["
                + "{\"id\":\"q1\",\"category\":\"scope\",\"question\":\"...\",\"evidence\":\"...\"}],"
                + "\"constraints_hit\":[\"pii\"]}. Each question must cite evidence or be \"assumption-check\".";

        String rendered = DEFAULTS.render("grill-questions", Map.of(
                "title", "Export orders CSV",
                "description", "Let SquadLead export orders as PII-safe CSV."));

        assertThat(rendered).isEqualTo(expected);
        assertThat(rendered).startsWith("[agent:grill]");
    }

    @Test
    void poDraftWithoutGrillRendersByteExact() {
        String expected = "[agent:po]" + "\n"
                + "Write the story for feature: Export orders CSV\n"
                + "Feature description: Let SquadLead export orders as PII-safe CSV.\n"
                + TITLE_INSTRUCTION
                + SCENARIO_FORMAT_SPEC
                + MULTI_STORY_SPEC;

        Map<String, Object> view = new HashMap<>();
        view.put("title", "Export orders CSV");
        view.put("description", "Let SquadLead export orders as PII-safe CSV.");
        String rendered = DEFAULTS.render("po-draft", view);

        assertThat(rendered).isEqualTo(expected);
        assertThat(rendered).startsWith("[agent:po]");
    }

    @Test
    void poDraftWithAnsweredAndParkedGrillRendersByteExact() {
        StringBuilder expected = new StringBuilder("[agent:po]").append('\n')
                .append("Write the story for feature: Export orders CSV\n")
                .append("Feature description: Let SquadLead export orders as PII-safe CSV.\n")
                .append(TITLE_INSTRUCTION)
                .append(SCENARIO_FORMAT_SPEC)
                .append(MULTI_STORY_SPEC)
                .append("Answer q1 (scope): CSV export only\n")
                .append("Answer q2 (risk): Rate-limited\n")
                .append("Parked (out of scope): q3, q4\n");

        Map<String, Object> view = new HashMap<>();
        view.put("title", "Export orders CSV");
        view.put("description", "Let SquadLead export orders as PII-safe CSV.");
        Map<String, Object> grill = new HashMap<>();
        grill.put("answers", List.of(
                Map.of("id", "q1", "category", "scope", "answer", "CSV export only"),
                Map.of("id", "q2", "category", "risk", "answer", "Rate-limited")));
        grill.put("parked", "q3, q4");
        view.put("grill", grill);
        String rendered = DEFAULTS.render("po-draft", view);

        assertThat(rendered).isEqualTo(expected.toString());
    }

    @Test
    void poReviseWithoutCommentsOrPreviousStoryRendersByteExact() {
        String expected = "[agent:po-revise]" + "\n"
                + "Revise only the lines these comments target; keep the rest verbatim, "
                + "including the exact Scenario/GIVEN/WHEN/THEN formatting of any untouched scenario.\n";

        Map<String, Object> view = new HashMap<>();
        view.put("comments", List.of());
        String rendered = DEFAULTS.render("po-revise", view);

        assertThat(rendered).isEqualTo(expected);
        assertThat(rendered).startsWith("[agent:po-revise]");
    }

    /** The optional "Current story" block adds one trailing newline beyond the hand-built Java
     * string — a documented Mustache variable-line quirk (plan "Assumptions & contingencies"),
     * harmless because prompts go to an LLM. */
    @Test
    void poReviseWithCommentsAndPreviousStoryRendersWithinTrailingNewlineTolerance() {
        StringBuilder expected = new StringBuilder("[agent:po-revise]").append('\n')
                .append("Revise only the lines these comments target; keep the rest verbatim, ")
                .append("including the exact Scenario/GIVEN/WHEN/THEN formatting of any untouched scenario.\n")
                .append("- ## Acceptance criteria: Add a THEN clause\n")
                .append("\nCurrent story:\n")
                .append("# Title\n\nSome story body.");

        Map<String, Object> view = new HashMap<>();
        view.put("comments", List.of(Map.of("target", "## Acceptance criteria", "text", "Add a THEN clause")));
        view.put("previousStory", Map.of("story", "# Title\n\nSome story body."));
        String rendered = DEFAULTS.render("po-revise", view);

        assertThat(rendered).isEqualTo(expected + "\n");
    }

    @Test
    void reviewSummaryRendersByteExact() {
        String expected = "[agent:review]" + "\n"
                + "Summarize this PR in one sentence for a human reviewer.\n"
                + "Change: openspec/changes/export-orders-csv\n"
                + "- T1 export-orders-csv: green, iterations=1\n"
                + "- T2 rate-limit-abuse: red, iterations=3\n";

        Map<String, Object> view = Map.of(
                "change", "openspec/changes/export-orders-csv",
                "results", List.of(
                        Map.of("taskId", "T1", "scenario", "export-orders-csv", "result", "green", "iterations", 1),
                        Map.of("taskId", "T2", "scenario", "rate-limit-abuse", "result", "red", "iterations", 3)));
        String rendered = DEFAULTS.render("review-summary", view);

        assertThat(rendered).isEqualTo(expected);
        assertThat(rendered).startsWith("[agent:review]");
    }

    @Test
    void releaseChangeNotesRendersByteExact() {
        String expected = "[agent:release]" + "\n"
                + "Write user-facing change notes (2-3 sentences) for this change, drawn from the "
                + "story's \"As a / So that\" and scenarios - never from commit messages.\n"
                + "Change: openspec/changes/export-orders-csv\n"
                + "Scenarios: export-orders-csv, rate-limit-abuse";

        Map<String, Object> view = Map.of(
                "change", "openspec/changes/export-orders-csv",
                "scenarios", "export-orders-csv, rate-limit-abuse");
        String rendered = DEFAULTS.render("release-change-notes", view);

        assertThat(rendered).isEqualTo(expected);
        assertThat(rendered).startsWith("[agent:release]");
    }

    @Test
    void releaseChangeNotesFallbackRendersByteExact() {
        String expected = "This release adds: export-orders-csv, rate-limit-abuse.";

        String rendered = DEFAULTS.render("release-change-notes-fallback",
                Map.of("scenarios", "export-orders-csv, rate-limit-abuse"));

        assertThat(rendered).isEqualTo(expected);
    }

    @Test
    void releaseRolloutPlanRendersByteExact() {
        String expected = "- Canary: 10% for 60 minutes\n"
                + "- Flag: export-orders-csv.enabled\n"
                + "- Rollback: flag off + deploy prev\n";

        Map<String, Object> view = Map.of(
                "canaryPct", 10, "soakMinutes", 60,
                "flag", "export-orders-csv.enabled", "rollback", "flag off + deploy prev");
        String rendered = DEFAULTS.render("release-rollout-plan", view);

        assertThat(rendered).isEqualTo(expected);
    }

    @Test
    void releaseMonitorRulesRendersByteExactIncludingEmptyList() {
        String expected = "- export-error-rate: http_5xx_rate > 2% over 15m -> file-card (owner: PO)\n"
                + "- export-abuse: exports_per_user_hour >= 10 for > 3 users in 1h -> file-card+notify-squad-lead (owner: SquadLead)\n";

        Map<String, Object> view = Map.of("rules", List.of(
                Map.of("id", "export-error-rate", "signal", "http_5xx_rate", "threshold", "> 2% over 15m",
                        "action", "file-card", "owner", "PO"),
                Map.of("id", "export-abuse", "signal", "exports_per_user_hour", "threshold", ">= 10 for > 3 users in 1h",
                        "action", "file-card+notify-squad-lead", "owner", "SquadLead")));
        assertThat(DEFAULTS.render("release-monitor-rules", view)).isEqualTo(expected);

        assertThat(DEFAULTS.render("release-monitor-rules", Map.of("rules", List.of()))).isEqualTo("");
    }

    @Test
    void releaseTestEvidenceRendersByteExact() {
        String expected = "## Verifier results\n"
                + "- T1 export-orders-csv: green (iterations=1)\n"
                + "\n## Traceability\n"
                + "- export-orders-csv -> test/export.spec.ts -> src/export.ts\n";

        Map<String, Object> view = Map.of(
                "results", List.of(Map.of("taskId", "T1", "scenario", "export-orders-csv", "result", "green", "iterations", 1)),
                "traceability", List.of(Map.of("scenario", "export-orders-csv", "testRef", "test/export.spec.ts", "codeRef", "src/export.ts")));
        assertThat(DEFAULTS.render("release-test-evidence", view)).isEqualTo(expected);
    }

    @Test
    void releaseTestEvidenceWithEmptyResultsAndTraceabilityStillEmitsBothHeadings() {
        String expected = "## Verifier results\n\n## Traceability\n";

        Map<String, Object> view = Map.of("results", List.of(), "traceability", List.of());
        assertThat(DEFAULTS.render("release-test-evidence", view)).isEqualTo(expected);
    }

    @Test
    void overrideDirTakesPrecedenceOverBundledDefaultPerTemplate(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("grill-questions.mustache"), "CUSTOM {{{title}}}");
        PromptTemplates templates = new PromptTemplates(dir.toString());

        assertThat(templates.render("grill-questions", Map.of("title", "X", "description", "Y")))
                .isEqualTo("CUSTOM X");

        // No po-draft.mustache override in dir -> falls back to the bundled default, per template.
        Map<String, Object> view = new HashMap<>();
        view.put("title", "X");
        view.put("description", "Y");
        assertThat(templates.render("po-draft", view)).startsWith("[agent:po]");
    }

    @Test
    void missingTemplateThrowsPdlcConfigException() {
        assertThatThrownBy(() -> DEFAULTS.render("nope", Map.of()))
                .isInstanceOf(PdlcConfigException.class)
                .hasMessageContaining("nope");
    }
}
