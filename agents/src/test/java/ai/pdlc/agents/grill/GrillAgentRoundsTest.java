package ai.pdlc.agents.grill;

import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.GrillRound;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.workflow.BoardCommentEvent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link GrillAgent#nextRound} adaptive-round contract (ADAPTIVE_GRILL_PLAN.md step 3/7) and the
 * reserved intake-confirmation question's deterministic fold (step 5). Uses a real {@link
 * GrillSkills} (the packaged pinned skill files are on the test classpath) with a mocked {@link
 * Ai}/{@link PromptRunner} — no network, no golden prompt-text assertions. */
class GrillAgentRoundsTest {

    private static final GrillSkills SKILLS = new GrillSkills();

    private static Profile profile() {
        BoardConfig board = new BoardConfig("in-memory", null, null, Map.of(), Map.of(), null);
        RepoConfig repo = new RepoConfig("in-memory", "local://x", "main", "openspec");
        NotifyConfig notify = new NotifyConfig("none", "none");
        AgentsConfig agents = new AgentsConfig("http://stub", null, Map.of());
        return new Profile("local", board, repo, notify, agents, Map.of());
    }

    private static Handoff envelope() {
        return new Handoff("grill-agent", "po-agent", "4412", CanonicalState.NEEDS_CLARIFICATION, List.of(), 0.8, List.of(), List.of());
    }

    private record Fixture(Ai ai, PromptRunner runner, BoardPort board, RepoPort repo, GrillAgent agent) {
    }

    private static Fixture fixture(String title, String description) {
        Ai ai = mock(Ai.class);
        PromptRunner runner = mock(PromptRunner.class);
        BoardPort board = mock(BoardPort.class);
        RepoPort repo = mock(RepoPort.class);
        when(ai.withDefaultLlm()).thenReturn(runner);
        when(runner.withReference(any())).thenReturn(runner);
        when(board.getItem(any())).thenReturn(new WorkItem("4412", "feature", title, description, CanonicalState.NEW, null, "orders", List.of()));
        when(board.listComments(any())).thenReturn(List.of());
        GrillAgent agent = new GrillAgent(ai, board, repo, new PromptTemplates(profile()), profile(), SKILLS);
        return new Fixture(ai, runner, board, repo, agent);
    }

    private static final WorkItemRef ITEM = new WorkItemRef("local", "4412");

    @Test
    void initialRoundAssignsSequentialIdsAndNeverStoresRecommendationsAsAnswers() {
        Fixture f = fixture("Export orders", "Export the filtered orders view.");
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"story","questions":[
                  {"category":"scope","question":"Which view?","recommendation":"Current filtered view.","evidence":"assumption-check"},
                  {"category":"users","question":"Who can export?","recommendation":"Sales and admin.","evidence":"assumption-check"}
                ],"constraints_hit":[],"summary":""}
                """);

        GrillRound round = f.agent().nextRound(ITEM, null);

        assertThat(round.handoff().questions()).extracting(GrillQuestion::id).containsExactly("q1", "q2");
        assertThat(round.handoff().questions()).allSatisfy(q -> {
            assertThat(q.status()).isEqualTo(GrillQuestion.Status.OPEN);
            assertThat(q.answer()).isNull();
        });
        assertThat(round.handoff().questions().get(0).question()).contains("Recommended answer: Current filtered view.");
    }

    @Test
    void stripsAMarkdownCodeFenceSomeRealLlmsWrapTheJsonResponseIn() {
        Fixture f = fixture("Export orders", "Export the filtered orders view.");
        when(f.runner().generateText(anyString())).thenReturn("""
                ```json
                {"type_decision":"story","questions":[
                  {"category":"scope","question":"Which view?","recommendation":"Current filtered view.","evidence":"assumption-check"}
                ],"constraints_hit":[],"summary":""}
                ```""");

        GrillRound round = f.agent().nextRound(ITEM, null);

        assertThat(round.handoff().questions()).extracting(GrillQuestion::id).containsExactly("q1");
    }

    @Test
    void dependentRoundAfterResolvedHistoryGetsNextIdRatherThanReusingOne() {
        Fixture f = fixture("Export orders", "Export the filtered orders view.");
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"story","questions":[
                  {"category":"dependency","question":"Reuse the streaming endpoint?","recommendation":"Yes.","evidence":"assumption-check"}
                ],"constraints_hit":[],"summary":""}
                """);
        List<GrillQuestion> history = List.of(
                new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "Which view?", "assumption-check", GrillQuestion.Status.ANSWERED, "current view", "PO"),
                new GrillQuestion("q2", GrillQuestion.Category.USERS, "Who?", "assumption-check", GrillQuestion.Status.PARKED, null, null),
                new GrillQuestion("q3", GrillQuestion.Category.ACCEPTANCE, "Format?", "assumption-check", GrillQuestion.Status.ANSWERED, "CSV", "PO"),
                new GrillQuestion("q4", GrillQuestion.Category.NFR, "Volume?", "assumption-check", GrillQuestion.Status.ANSWERED, "50k", "PO"));
        GrillHandoff previous = new GrillHandoff(envelope(), "story", history, List.of("q2"), List.of());

        GrillRound round = f.agent().nextRound(ITEM, previous);

        assertThat(round.handoff().questions()).extracting(GrillQuestion::id)
                .containsExactly("q1", "q2", "q3", "q4", "q5");
    }

    @Test
    void answeredRiskQuestionIsNotRegeneratedEvenWithEmptyModelFrontier() {
        Fixture f = fixture("Export PII data", "Contains payment info.");
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"story","questions":[],"constraints_hit":[],"summary":"Everything settled."}
                """);
        List<GrillQuestion> history = List.of(new GrillQuestion("q1", GrillQuestion.Category.RISK,
                "Touches PII?", "assumption-check", GrillQuestion.Status.ANSWERED, "Audited and rate-limited", "PO"));
        GrillHandoff previous = new GrillHandoff(envelope(), "story", history, List.of(), List.of());

        GrillRound round = f.agent().nextRound(ITEM, previous);

        assertThat(round.handoff().questions()).hasSize(1); // no second risk question minted
        assertThat(round.summary()).isEqualTo("Everything settled.");
    }

    @Test
    void riskKeywordWithNoExistingRiskQuestionMintsOneEvenOnAnEmptyModelFrontier() {
        Fixture f = fixture("Export PII data", "Contains payment info.");
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"story","questions":[],"constraints_hit":[],"summary":"ignored - frontier not actually empty"}
                """);

        GrillRound round = f.agent().nextRound(ITEM, null);

        assertThat(round.handoff().questions()).hasSize(1);
        assertThat(round.handoff().questions().get(0).category()).isEqualTo(GrillQuestion.Category.RISK);
        assertThat(round.handoff().questions().get(0).status()).isEqualTo(GrillQuestion.Status.OPEN);
    }

    @Test
    void malformedJsonThrowsRatherThanCompletingEmpty() {
        Fixture f = fixture("Export orders", "csv export");
        when(f.runner().generateText(anyString())).thenReturn("not json at all");

        assertThatThrownBy(() -> f.agent().nextRound(ITEM, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingQuestionsArrayThrows() {
        Fixture f = fixture("Export orders", "csv export");
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"story","constraints_hit":[],"summary":"done"}
                """);

        assertThatThrownBy(() -> f.agent().nextRound(ITEM, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownCategoryThrows() {
        Fixture f = fixture("Export orders", "csv export");
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"story","questions":[{"category":"bogus","question":"?","recommendation":"?","evidence":"assumption-check"}],"constraints_hit":[],"summary":""}
                """);

        assertThatThrownBy(() -> f.agent().nextRound(ITEM, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void buildCategoryIsRejectedForAGrillGeneratedQuestion() {
        Fixture f = fixture("Export orders", "csv export");
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"story","questions":[{"category":"build","question":"?","recommendation":"?","evidence":"assumption-check"}],"constraints_hit":[],"summary":""}
                """);

        assertThatThrownBy(() -> f.agent().nextRound(ITEM, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankSummaryOnAGenuinelyEmptyFrontierThrows() {
        Fixture f = fixture("Export orders", "csv export"); // no risk keyword
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"story","questions":[],"constraints_hit":[],"summary":""}
                """);

        assertThatThrownBy(() -> f.agent().nextRound(ITEM, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsStartingARoundWhilePreviousQuestionsRemainOpenBeforeAnyLlmCall() {
        Fixture f = fixture("Export orders", "csv export");
        List<GrillQuestion> open = List.of(new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "?", "assumption-check", GrillQuestion.Status.OPEN, null, null));
        GrillHandoff previous = new GrillHandoff(envelope(), "story", open, List.of(), List.of());

        assertThatThrownBy(() -> f.agent().nextRound(ITEM, previous)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(f.ai());
    }

    @Test
    void typeDecisionIsClassifiedOnceAndStaysStableAcrossLaterRounds() {
        Fixture f = fixture("Export orders", "csv export");
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"bug","questions":[{"category":"scope","question":"?","recommendation":"?","evidence":"assumption-check"}],"constraints_hit":[],"summary":""}
                """);
        List<GrillQuestion> history = List.of(new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "Prior?", "assumption-check", GrillQuestion.Status.ANSWERED, "yes", "PO"));
        GrillHandoff previous = new GrillHandoff(envelope(), "story", history, List.of(), List.of());

        GrillRound round = f.agent().nextRound(ITEM, previous);

        assertThat(round.handoff().typeDecision()).isEqualTo("story"); // classified at intake start, not re-decided
    }

    @Test
    void constraintsHitMergeAcrossRoundsInOrderWithoutDuplicates() {
        Fixture f = fixture("Export orders", "csv export");
        when(f.runner().generateText(anyString())).thenReturn("""
                {"type_decision":"story","questions":[{"category":"scope","question":"?","recommendation":"?","evidence":"assumption-check"}],"constraints_hit":["rate-limit","pii"],"summary":""}
                """);
        List<GrillQuestion> history = List.of(new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "Prior?", "assumption-check", GrillQuestion.Status.ANSWERED, "yes", "PO"));
        GrillHandoff previous = new GrillHandoff(envelope(), "story", history, List.of(), List.of("pii"));

        GrillRound round = f.agent().nextRound(ITEM, previous);

        assertThat(round.handoff().constraintsHit()).containsExactly("pii", "rate-limit");
    }

    // -- reserved intake-confirmation question fold (ADAPTIVE_GRILL_PLAN.md step 5) --------------

    private static GrillQuestion confirmationQuestion() {
        return new GrillQuestion("q9", GrillQuestion.Category.SCOPE,
                "Confirm shared understanding: everything settled.\n\nReply confirm to proceed, or describe corrections.",
                GrillQuestion.INTAKE_CONFIRMATION_EVIDENCE, GrillQuestion.Status.OPEN, null, null);
    }

    @Test
    void parkingTheConfirmationQuestionIsANoOpAndLeavesItOpen() {
        GrillHandoff previous = new GrillHandoff(envelope(), "story", List.of(confirmationQuestion()), List.of(), List.of());

        GrillHandoff result = GrillAgent.evaluateAnswers(previous, List.of(new BoardCommentEvent("c1", "po@acme", "q9: park")));

        assertThat(result.questions().get(0).status()).isEqualTo(GrillQuestion.Status.OPEN);
        assertThat(result.parked()).doesNotContain("q9");
        assertThat(result.allQuestionsResolved()).isFalse();
    }

    @Test
    void confirmingTheConfirmationQuestionAnswersIt() {
        GrillHandoff previous = new GrillHandoff(envelope(), "story", List.of(confirmationQuestion()), List.of(), List.of());

        GrillHandoff result = GrillAgent.evaluateAnswers(previous, List.of(new BoardCommentEvent("c1", "po@acme", "q9: confirm")));

        GrillQuestion q9 = result.questions().get(0);
        assertThat(q9.status()).isEqualTo(GrillQuestion.Status.ANSWERED);
        assertThat(q9.answer()).isEqualTo("confirm");
        assertThat(q9.answeredBy()).isEqualTo("po@acme");
    }

    @Test
    void correctingTheConfirmationQuestionRecordsTheCorrectionAsAnAnswerNotAPark() {
        GrillHandoff previous = new GrillHandoff(envelope(), "story", List.of(confirmationQuestion()), List.of(), List.of());

        GrillHandoff result = GrillAgent.evaluateAnswers(previous,
                List.of(new BoardCommentEvent("c1", "po@acme", "q9: actually, also cap admin at 20/hour")));

        GrillQuestion q9 = result.questions().get(0);
        assertThat(q9.status()).isEqualTo(GrillQuestion.Status.ANSWERED);
        assertThat(q9.answer()).isEqualTo("actually, also cap admin at 20/hour");
        assertThat(result.parked()).isEmpty();
    }
}
