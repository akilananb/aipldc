package ai.pdlc.agents.review;

import ai.pdlc.core.domain.ReviewFinding;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.VerifierResult;
import ai.pdlc.core.workflow.BuildResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewAgentTest {

    private static final Task.TaskBudget BUDGET = new Task.TaskBudget(6, 100_000L, Duration.ofMinutes(10));

    private static Task task(String id, String scenario) {
        return new Task(id, "Implement " + scenario, "orders", scenario, List.of("src/export.js"),
                "test/export.test.js", BUDGET, List.of());
    }

    @Test
    void greenResultProducesTraceabilityRowAndNoFindings() {
        Task t1 = task("T1", "rate-limit");
        VerifierResult verifier = new VerifierResult("green", Map.of("rate-limit", true), true, "all pass");
        BuildResult result = new BuildResult("T1", "sha1", verifier, 3, 10_000L, List.of("src/export.js"), "implemented", null);

        List<ReviewHandoff.TraceabilityRow> rows = ReviewAgent.buildTraceability(List.of(t1), List.of(result));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).scenario()).isEqualTo("rate-limit");
        assertThat(rows.get(0).testRef()).isEqualTo("test/export.test.js :: scenario: rate-limit");
        assertThat(rows.get(0).codeRef()).isEqualTo("src/export.js");

        assertThat(ReviewAgent.buildFindings(List.of(t1), List.of(result))).isEmpty();
    }

    @Test
    void redVerifierProducesBlockerFinding() {
        Task t1 = task("T1", "rate-limit");
        VerifierResult verifier = new VerifierResult("red", Map.of("rate-limit", false), true, "assertion failed");
        BuildResult result = new BuildResult("T1", "sha1", verifier, 6, 10_000L, List.of("src/export.js"), "stuck", "same failing set for 2 iterations");

        List<ReviewFinding> findings = ReviewAgent.buildFindings(List.of(t1), List.of(result));
        assertThat(findings).hasSize(2); // verifier blocker + escalation should
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.severity()).isEqualTo(ReviewFinding.Severity.BLOCKER);
            assertThat(f.category()).isEqualTo("verifier");
        });
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.severity()).isEqualTo(ReviewFinding.Severity.SHOULD);
            assertThat(f.category()).isEqualTo("escalation");
        });
    }

    @Test
    void scopeViolationProducesBlockerFindingInsteadOfVerifierFinding() {
        Task t1 = task("T1", "rate-limit");
        VerifierResult verifier = new VerifierResult("green", Map.of("rate-limit", true), false, "diff touched tests/verifier.js");
        BuildResult result = new BuildResult("T1", "sha1", verifier, 2, 5_000L, List.of("src/export.js", "tests/verifier.js"), "forbidden edit", "wanted to edit the verifier");

        List<ReviewFinding> findings = ReviewAgent.buildFindings(List.of(t1), List.of(result));
        assertThat(findings).extracting(ReviewFinding::category).contains("scope", "escalation");
        assertThat(findings).noneMatch(f -> f.category().equals("verifier"));
    }

    @Test
    void multipleTasksEachProduceTheirOwnTraceabilityRow() {
        Task t1 = task("T1", "rate-limit");
        Task t2 = task("T2", "audit");
        VerifierResult green = new VerifierResult("green", Map.of(), true, "ok");
        BuildResult r1 = new BuildResult("T1", "sha1", green, 1, 1000L, List.of(), "ok", null);
        BuildResult r2 = new BuildResult("T2", "sha2", green, 1, 1000L, List.of(), "ok", null);

        List<ReviewHandoff.TraceabilityRow> rows = ReviewAgent.buildTraceability(List.of(t1, t2), List.of(r1, r2));
        assertThat(rows).extracting(ReviewHandoff.TraceabilityRow::scenario).containsExactly("rate-limit", "audit");
    }
}
