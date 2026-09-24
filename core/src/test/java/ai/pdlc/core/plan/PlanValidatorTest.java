package ai.pdlc.core.plan;

import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.Task;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanValidatorTest {

    private static final Handoff ENVELOPE = new Handoff("plan-agent", "build-worker", "4413",
            CanonicalState.PLANNED, List.of(), 0.9, List.of(), List.of());
    private static final Task.TaskBudget BUDGET = new Task.TaskBudget(6, 100_000L, Duration.ofMinutes(10));

    private static Task task(String id, String scenario, List<String> touches, List<String> blockedBy) {
        return new Task(id, "Implement " + scenario, "brief", "orders", "main", scenario, touches, "test/export.test.js", BUDGET, blockedBy);
    }

    @Test
    void validSequentialPlanPassesAllThreeChecks() {
        Task t1 = task("T1", "a", List.of("src/export.js"), List.of());
        Task t2 = task("T2", "b", List.of("src/export.js"), List.of("T1"));
        PlanHandoff plan = new PlanHandoff(ENVELOPE, List.of(t1, t2), List.of(List.of("T1"), List.of("T2")));

        assertThat(PlanValidator.coverageOk(plan, List.of("a", "b"))).isTrue();
        assertThat(PlanValidator.conflictFree(plan)).isTrue();
        assertThat(PlanValidator.dagOk(plan)).isTrue();
        assertThat(PlanValidator.violations(plan, List.of("a", "b"))).isEmpty();
    }

    @Test
    void validParallelPlanWithIndependentFilesPassesAllThreeChecks() {
        Task t1 = task("T1", "a", List.of("src/export.js"), List.of());
        Task t2 = task("T2", "b", List.of("src/other.js"), List.of());
        PlanHandoff plan = new PlanHandoff(ENVELOPE, List.of(t1, t2), List.of(List.of("T1", "T2")));

        assertThat(PlanValidator.coverageOk(plan, List.of("a", "b"))).isTrue();
        assertThat(PlanValidator.conflictFree(plan)).isTrue();
        assertThat(PlanValidator.dagOk(plan)).isTrue();
        assertThat(PlanValidator.violations(plan, List.of("a", "b"))).isEmpty();
    }

    @Test
    void missingScenarioFailsCoverage() {
        Task t1 = task("T1", "a", List.of("src/export.js"), List.of());
        PlanHandoff plan = new PlanHandoff(ENVELOPE, List.of(t1), List.of(List.of("T1")));

        assertThat(PlanValidator.coverageOk(plan, List.of("a", "b"))).isFalse();
        assertThat(PlanValidator.violations(plan, List.of("a", "b")))
                .anySatisfy(v -> assertThat(v).contains("coverage: scenario \"b\" has no task"));
    }

    @Test
    void twoTasksInSameWaveTouchingSameFileFailsConflictFree() {
        Task t1 = task("T1", "a", List.of("src/export.js"), List.of());
        Task t2 = task("T2", "b", List.of("src/export.js"), List.of());
        PlanHandoff plan = new PlanHandoff(ENVELOPE, List.of(t1, t2), List.of(List.of("T1", "T2")));

        assertThat(PlanValidator.conflictFree(plan)).isFalse();
        assertThat(PlanValidator.violations(plan, List.of("a", "b")))
                .anySatisfy(v -> assertThat(v).contains("conflict: wave 0 has two tasks touching src/export.js"));
    }

    @Test
    void blockerInTheSameOrLaterWaveFailsDag() {
        Task t1 = task("T1", "a", List.of("src/export.js"), List.of("T2")); // blocked by a later task
        Task t2 = task("T2", "b", List.of("src/export.js"), List.of());
        PlanHandoff plan = new PlanHandoff(ENVELOPE, List.of(t1, t2), List.of(List.of("T1"), List.of("T2")));

        assertThat(PlanValidator.dagOk(plan)).isFalse();
        assertThat(PlanValidator.violations(plan, List.of("a", "b")))
                .anySatisfy(v -> assertThat(v).contains("dag: T1 is blocked by T2 which is not in an earlier wave"));
    }

    @Test
    void taskNamingUnknownScenarioIsAViolation() {
        Task t1 = task("T1", "not-a-scenario", List.of("src/export.js"), List.of());
        PlanHandoff plan = new PlanHandoff(ENVELOPE, List.of(t1), List.of(List.of("T1")));

        assertThat(PlanValidator.violations(plan, List.of("a")))
                .anySatisfy(v -> assertThat(v).contains("coverage: task T1 names unknown scenario \"not-a-scenario\""));
    }
}
