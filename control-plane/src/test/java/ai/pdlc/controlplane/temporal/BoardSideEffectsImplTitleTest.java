package ai.pdlc.controlplane.temporal;

import ai.pdlc.core.domain.Task;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain-unit coverage for the title-validation guards {@link BoardSideEffectsImpl#publishStory}
 * and {@link BoardSideEffectsImpl#publishTasks} rely on — no Spring/Testcontainers needed since
 * both are deterministic package-private statics. Regression for the bug where a story whose only
 * heading was {@code ## Acceptance criteria} got stored on the board with that as its title. */
class BoardSideEffectsImplTitleTest {

    @Test
    void firstHeadingOrDefaultSkipsStructuralSectionHeadingsAndUsesTheRealTitle() {
        String story = """
                # Export the filtered orders view to CSV

                ## Acceptance Criteria
                Scenario: export-under-limit
                  Given a filtered view
                  When the user exports
                  Then a CSV downloads
                """;

        assertThat(BoardSideEffectsImpl.firstHeadingOrDefault(story, "Untitled story"))
                .isEqualTo("Export the filtered orders view to CSV");
    }

    @Test
    void firstHeadingOrDefaultFallsBackWhenOnlyStructuralHeadingsArePresent() {
        String story = """
                ## Goals
                - Let sales admins export filtered orders

                ## Acceptance Criteria
                Scenario: export-under-limit
                  Given a filtered view
                  When the user exports
                  Then a CSV downloads

                ### Out of Scope
                - bulk export
                """;

        assertThat(BoardSideEffectsImpl.firstHeadingOrDefault(story, "Untitled story")).isEqualTo("Untitled story");
    }

    @Test
    void firstHeadingOrDefaultFallsBackWhenOnlyAStoryContainerHeadingIsPresent() {
        // The observed live-LLM shape: a bare "## Story" container wraps the "As a ... I want ..."
        // narrative instead of a descriptive title heading - also structural, must be skipped.
        String story = """
                ## Story
                Area: orders
                As a Sales Ops user, I want to export the filtered orders view to CSV.

                ## Acceptance criteria
                Scenario: export-under-limit
                  GIVEN a filtered view
                  WHEN the user exports
                  THEN a CSV downloads
                """;

        assertThat(BoardSideEffectsImpl.firstHeadingOrDefault(story, "Untitled story")).isEqualTo("Untitled story");
    }

    @Test
    void firstHeadingOrDefaultFallsBackWhenNoHeadingIsPresentAtAll() {
        String story = "Scenario: export-under-limit\n  GIVEN a filtered view\n  WHEN the user exports\n  THEN a CSV downloads\n";

        assertThat(BoardSideEffectsImpl.firstHeadingOrDefault(story, "Untitled story")).isEqualTo("Untitled story");
    }

    @Test
    void taskTitleOrDefaultPassesThroughANonBlankTitle() {
        Task task = new Task("T1", "Rate limit on /export", "brief", "orders", "export-under-limit",
                List.of("orders-service/export"), "orders-service/export.spec.ts",
                new Task.TaskBudget(6, 120_000L, Duration.ofMinutes(10)), List.of());

        assertThat(BoardSideEffectsImpl.taskTitleOrDefault(task)).isEqualTo("Rate limit on /export");
    }

    @Test
    void taskTitleOrDefaultFallsBackToTaskIdWhenTitleIsBlank() {
        Task task = new Task("T1", "   ", "brief", "orders", "export-under-limit",
                List.of("orders-service/export"), "orders-service/export.spec.ts",
                new Task.TaskBudget(6, 120_000L, Duration.ofMinutes(10)), List.of());

        assertThat(BoardSideEffectsImpl.taskTitleOrDefault(task)).isEqualTo("Task T1");
    }

    @Test
    void taskTitleOrDefaultIgnoresABlankScenarioWhenTitleIsNonBlank() {
        // The title is agent-written and independent of the scenario name, so a blank scenario
        // (a separate plan-validation concern, not this guard's) never triggers the fallback.
        Task task = new Task("T1", "Rate limit on /export", "brief", "orders", "  ",
                List.of("orders-service/export"), "orders-service/export.spec.ts",
                new Task.TaskBudget(6, 120_000L, Duration.ofMinutes(10)), List.of());

        assertThat(BoardSideEffectsImpl.taskTitleOrDefault(task)).isEqualTo("Rate limit on /export");
    }
}
