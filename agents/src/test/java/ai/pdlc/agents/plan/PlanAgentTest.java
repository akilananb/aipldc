package ai.pdlc.agents.plan;

import ai.pdlc.adapters.inmemory.InMemoryRepoAdapter;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.WorkItemRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanAgentTest {

    private InMemoryRepoAdapter repo;
    private Profile profile;
    private PlanAgent planAgent;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRepoAdapter();
        repo.writeFiles("main", Map.of("openspec/config.yaml", """
                areas:
                  orders:
                    touches:
                      - src/export.js
                    test: test/export.test.js
                """), "seed", "test-fixture");
        profile = new Profile("local", null, new RepoConfig("local-git", "x", "main", "openspec"), null, null, Map.of());
        planAgent = new PlanAgent(repo, profile);
    }

    @Test
    void oneTaskPerScenarioSequentialWavesWhenTheyShareAFile() {
        PoHandoff po = poHandoff(List.of("export-current-view", "rate-limit", "audit"));
        PlanHandoff plan = planAgent.plan(new WorkItemRef("local", "4413"), po);

        assertThat(plan.tasks()).hasSize(3);
        assertThat(plan.waves()).hasSize(3); // all three touch src/export.js -> fully sequential
        assertThat(plan.waves()).containsExactly(List.of("T1"), List.of("T2"), List.of("T3"));
        assertThat(plan.task("T1").scenario()).isEqualTo("export-current-view");
        assertThat(plan.task("T2").scenario()).isEqualTo("rate-limit");
        assertThat(plan.task("T3").scenario()).isEqualTo("audit");
        for (var t : plan.tasks()) {
            assertThat(t.touches()).containsExactly("src/export.js");
            assertThat(t.testPath()).isEqualTo("test/export.test.js");
            assertThat(t.area()).isEqualTo("orders");
        }
        assertThat(plan.task("T1").blockedBy()).isEmpty();
        assertThat(plan.task("T2").blockedBy()).containsExactly("T1");
        assertThat(plan.task("T3").blockedBy()).containsExactly("T1", "T2");

        assertThat(PlanValidator.coverageOk(plan, po.scenarios())).isTrue();
        assertThat(PlanValidator.conflictFree(plan)).isTrue();
        assertThat(PlanValidator.dagOk(plan)).isTrue();
    }

    @Test
    void missingAreaConfigFallsBackToNamingConvention() {
        InMemoryRepoAdapter emptyRepo = new InMemoryRepoAdapter(); // no openspec/config.yaml written
        PlanAgent fallbackAgent = new PlanAgent(emptyRepo, profile);
        PoHandoff po = poHandoffForArea("widgets", List.of("scenario-a"));

        PlanHandoff plan = fallbackAgent.plan(new WorkItemRef("local", "1"), po);

        assertThat(plan.tasks()).hasSize(1);
        assertThat(plan.tasks().get(0).touches()).containsExactly("src/widgets.js");
        assertThat(plan.tasks().get(0).testPath()).isEqualTo("test/widgets.test.js");
    }

    private static PoHandoff poHandoff(List<String> scenarios) {
        return poHandoffForArea("orders", scenarios);
    }

    private static PoHandoff poHandoffForArea(String area, List<String> scenarios) {
        Handoff envelope = new Handoff("po-agent", "plan-agent", "4413", CanonicalState.AWAITING_G1,
                List.of(), 0.9, List.of(), List.of());
        return new PoHandoff(envelope, "4412", "openspec/changes/export-orders-csv", scenarios,
                Map.of(), List.of(area), Map.of("I", "pass"), List.of(), Map.of());
    }
}
