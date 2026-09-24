package ai.pdlc.agents.plan;

import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.ProjectMeta;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PlanDecision;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.WorkItemRef;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves {@link PlanAgent} fails closed on malformed model output rather than ever synthesizing a
 * successful/fallback {@link PlanDecision} - a plan agent that silently invents a valid-looking
 * decision from garbage output is exactly the failure mode {@code PlanningLoop} relies on this
 * class never producing. Does not assert exact prompt text, field forwarding, or mock echoes -
 * only the parse/validate boundary.
 */
class PlanAgentTest {

    private static final WorkItemRef STORY = new WorkItemRef("local", "4413");

    private static Profile profile() {
        return new Profile("local",
                new ProjectMeta("local", "local", null, List.of(), "", null),
                new BoardConfig("in-memory", null, null, Map.of(), Map.of(), null),
                List.of(new RepoConfig("main", "in-memory", "local://x", "main", "openspec", List.of(), true)),
                new NotifyConfig("none", "none"),
                new AgentsConfig("http://stub", null, Map.of()),
                Map.of());
    }

    private static PoHandoff po() {
        Handoff envelope = new Handoff("po-agent", "plan-agent", STORY.boardId(),
                CanonicalState.APPROVED, List.of(), 0.9, List.of(), List.of());
        return new PoHandoff(envelope, STORY.boardId(), "openspec/changes/export", List.of("export-csv"),
                Map.of(), List.of("orders"), Map.of(), List.of(), Map.of());
    }

    private static PlanAgent agentReturning(String rawResponse) {
        Ai ai = mock(Ai.class);
        PromptRunner runner = mock(PromptRunner.class);
        when(ai.withDefaultLlm()).thenReturn(runner);
        when(runner.generateText(anyString())).thenReturn(rawResponse);
        PromptTemplates templates = mock(PromptTemplates.class);
        when(templates.render(eq("plan-next-step"), any())).thenReturn("rendered prompt");
        ProjectDirectory projects = mock(ProjectDirectory.class);
        when(projects.project("local")).thenReturn(profile());
        return new PlanAgent(ai, templates, profile(), projects);
    }

    @Test
    void malformedJsonThrowsRatherThanReturningAFallbackDecision() {
        PlanAgent agent = agentReturning("not json at all");

        assertThatThrownBy(() -> agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed plan decision JSON");
    }

    @Test
    void missingActionThrows() {
        PlanAgent agent = agentReturning("{\"reason\":\"x\",\"questions\":[],\"paths\":[],\"tasks\":[]}");

        assertThatThrownBy(() -> agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing an action");
    }

    @Test
    void unknownActionThrows() {
        PlanAgent agent = agentReturning("{\"action\":\"MAYBE\",\"reason\":\"x\",\"questions\":[],\"paths\":[],\"tasks\":[]}");

        assertThatThrownBy(() -> agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown action");
    }

    @Test
    void missingReasonThrows() {
        PlanAgent agent = agentReturning("{\"action\":\"CONSULT\",\"questions\":[\"where?\"],\"paths\":[],\"tasks\":[]}");

        assertThatThrownBy(() -> agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing a nonblank reason");
    }

    @Test
    void consultWithNoQuestionsIsAnInconsistentPayloadAndThrows() {
        PlanAgent agent = agentReturning("{\"action\":\"CONSULT\",\"reason\":\"need evidence\",\"questions\":[],\"paths\":[],\"tasks\":[]}");

        assertThatThrownBy(() -> agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONSULT with no questions");
    }

    @Test
    void finalizeWithNoTasksIsAnInconsistentPayloadAndThrows() {
        PlanAgent agent = agentReturning("{\"action\":\"FINALIZE\",\"reason\":\"done\",\"questions\":[],\"paths\":[],\"tasks\":[]}");

        assertThatThrownBy(() -> agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FINALIZE with no tasks");
    }

    @Test
    void aWellFormedConsultDecisionParsesSuccessfully() {
        PlanAgent agent = agentReturning(
                "{\"action\":\"CONSULT\",\"reason\":\"need evidence\",\"questions\":[\"Where is X?\"],\"paths\":[\"src/x.ts\"],\"tasks\":[]}");

        PlanDecision decision = agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), true);

        assertThat(decision.action()).isEqualTo(PlanDecision.Action.CONSULT);
        assertThat(decision.questions()).containsExactly("Where is X?");
        assertThat(decision.paths()).containsExactly("src/x.ts");
        assertThat(decision.tasks()).isEmpty();
    }

    @Test
    void aWellFormedFinalizeDecisionParsesSuccessfully() {
        PlanAgent agent = agentReturning("""
                {"action":"FINALIZE","reason":"grounded","questions":[],"paths":[],"tasks":[
                  {"id":"T1","title":"Export as CSV","description":"desc","area":"orders","scenario":"export-csv",
                   "touches":["src/export.ts"],"testPath":"test/export.test.ts","blockedBy":[]}
                ]}
                """);

        PlanDecision decision = agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), false);

        assertThat(decision.action()).isEqualTo(PlanDecision.Action.FINALIZE);
        assertThat(decision.tasks()).hasSize(1);
        assertThat(decision.tasks().get(0).id()).isEqualTo("T1");
    }

    @Test
    void blockedDecisionRequiresOnlyAReason() {
        PlanAgent agent = agentReturning("{\"action\":\"BLOCKED\",\"reason\":\"contradictory evidence\",\"questions\":[],\"paths\":[],\"tasks\":[]}");

        PlanDecision decision = agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), false);

        assertThat(decision.action()).isEqualTo(PlanDecision.Action.BLOCKED);
        assertThat(decision.reason()).isEqualTo("contradictory evidence");
    }

    @Test
    void stripsAMarkdownCodeFenceAroundTheJsonResponse() {
        PlanAgent agent = agentReturning("```json\n{\"action\":\"BLOCKED\",\"reason\":\"x\",\"questions\":[],\"paths\":[],\"tasks\":[]}\n```");

        PlanDecision decision = agent.nextStep(STORY, po(), "# story", List.of(), List.of(), List.of(), false);

        assertThat(decision.action()).isEqualTo(PlanDecision.Action.BLOCKED);
    }
}
