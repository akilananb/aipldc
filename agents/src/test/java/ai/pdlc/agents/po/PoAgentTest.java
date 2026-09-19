package ai.pdlc.agents.po;

import ai.pdlc.agents.fixtures.DemoFixtures;
import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.workflow.AgentActivities;
import ai.pdlc.core.workflow.StoryDraft;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DataConverter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Locks the fix for the bug where PO story drafts omit the {@code Feature: ... · Area: X} marker
 * {@link StoryParser#area} needs, causing the plan step's area lookup (openspec/config.yaml) to
 * miss instead of resolving the story's real area (root-caused after a live build/deploy run
 * touched the wrong file). */
class PoAgentTest {

    @Test
    void injectAreaInsertsRightAfterFirstHeadingAndStoryParserThenResolvesIt() {
        String story = """
                ## Story

                **Title:** Export the filtered orders view to CSV

                **As a** Sales Ops user
                """;

        String withArea = PoAgent.injectArea(story, "orders");

        assertThat(withArea.lines().toList()).containsSequence("## Story", "Area: orders");
        assertThat(StoryParser.area(withArea)).isEqualTo("orders");
    }

    @Test
    void injectAreaPrependsWhenNoHeadingPresent() {
        String story = "**Title:** Export the filtered orders view to CSV\n";

        String withArea = PoAgent.injectArea(story, "orders");

        assertThat(withArea).startsWith("Area: orders\n");
        assertThat(StoryParser.area(withArea)).isEqualTo("orders");
    }

    @Test
    void injectTitlePrependsHeadingWhenStoryHasNoneOfItsOwn() {
        String story = "## Acceptance criteria\nScenario: export\n  GIVEN a filtered view\n  WHEN the user exports\n  THEN a CSV downloads\n";

        String withTitle = PoAgent.injectTitle(story, "Export the filtered orders view to CSV");

        assertThat(withTitle).startsWith("# Export the filtered orders view to CSV\n\n");
        assertThat(StoryParser.title(withTitle)).isEqualTo("Export the filtered orders view to CSV");
    }

    @Test
    void injectedFallbackTitlesAreDisambiguatedPerSplitPartSoChangeSlugsDontCollide() {
        // Mirrors PoAgent.draft's per-part loop: part 0 gets the feature title verbatim, later
        // parts get "(N)" appended - otherwise two split stories that both lack their own title
        // heading would inject the identical title and extractChange would slug them identically.
        String part1 = "## Acceptance criteria\nScenario: export\n  GIVEN x\n  WHEN y\n  THEN z\n";
        String part2 = "## Acceptance criteria\nScenario: restrict\n  GIVEN x\n  WHEN y\n  THEN z\n";
        String featureTitle = "Export the filtered orders view to CSV";

        String story1 = PoAgent.injectTitle(part1, featureTitle);
        String story2 = PoAgent.injectTitle(part2, featureTitle + " (2)");

        assertThat(PoAgent.extractChange(story1)).isNotEqualTo(PoAgent.extractChange(story2));
        assertThat(PoAgent.extractChange(story2)).endsWith("-2");
    }

    @Test
    void parseFollowUpsAssignsSequentialPoIdsAndSkipsUnknownCategory() {
        String output = """
                ===QUESTIONS===
                [
                  {"category": "users", "question": "Which roles may export?", "evidence": "assumption-check"},
                  {"category": "scope", "question": "Only the current filtered view?", "evidence": "q1"},
                  {"category": "weather", "question": "Is it sunny?", "evidence": "assumption-check"}
                ]
                """;

        java.util.List<ai.pdlc.core.domain.GrillQuestion> followUps = PoAgent.parseFollowUps(output, 2);

        assertThat(followUps).extracting(ai.pdlc.core.domain.GrillQuestion::id).containsExactly("po2", "po3");
        assertThat(followUps).extracting(ai.pdlc.core.domain.GrillQuestion::category)
                .containsExactly(ai.pdlc.core.domain.GrillQuestion.Category.USERS, ai.pdlc.core.domain.GrillQuestion.Category.SCOPE);
        assertThat(followUps).allMatch(q -> q.status() == ai.pdlc.core.domain.GrillQuestion.Status.OPEN);
    }

    @Test
    void parseFollowUpsReturnsNullWithoutSentinel() {
        String output = "## Story\n\n**Title:** Export the filtered orders view to CSV\n";

        assertThat(PoAgent.parseFollowUps(output, 1)).isNull();
    }

    @Test
    void nextPoIndexSkipsPastHighestExistingPoNumberEvenWithGaps() {
        // po1 is missing (a prior round's unknown-category dto left a gap) - counting existing po*
        // questions (old, buggy behaviour) would return 3, colliding with the already-used po3.
        ai.pdlc.core.domain.GrillQuestion po2 = new ai.pdlc.core.domain.GrillQuestion("po2",
                ai.pdlc.core.domain.GrillQuestion.Category.USERS, "q", "assumption-check", ai.pdlc.core.domain.GrillQuestion.Status.OPEN, null, null);
        ai.pdlc.core.domain.GrillQuestion po3 = new ai.pdlc.core.domain.GrillQuestion("po3",
                ai.pdlc.core.domain.GrillQuestion.Category.SCOPE, "q", "assumption-check", ai.pdlc.core.domain.GrillQuestion.Status.OPEN, null, null);
        ai.pdlc.core.domain.Handoff envelope = new ai.pdlc.core.domain.Handoff("grill-agent", "po-agent", "4412",
                ai.pdlc.core.domain.CanonicalState.NEEDS_CLARIFICATION, java.util.List.of(), 0.8, java.util.List.of(), java.util.List.of());
        ai.pdlc.core.domain.GrillHandoff grill = new ai.pdlc.core.domain.GrillHandoff(envelope, "story",
                java.util.List.of(po2, po3), java.util.List.of(), java.util.List.of());

        assertThat(PoAgent.nextPoIndex(grill)).isEqualTo(4);
    }

    @Test
    void nextPoIndexReturnsOneForNullOrEmptyGrill() {
        assertThat(PoAgent.nextPoIndex(null)).isEqualTo(1);
    }

    @Test
    void parseFollowUpsStripsJsonCodeFence() {
        String output = """
                ===QUESTIONS===
                ```json
                [
                  {"category": "users", "question": "Which roles may export?", "evidence": "assumption-check"}
                ]
                ```
                """;

        java.util.List<ai.pdlc.core.domain.GrillQuestion> followUps = PoAgent.parseFollowUps(output, 1);

        assertThat(followUps).extracting(ai.pdlc.core.domain.GrillQuestion::id).containsExactly("po1");
    }

    @Test
    void parseFollowUpsSkipsBlankQuestion() {
        String output = """
                ===QUESTIONS===
                [
                  {"category": "users", "question": "  ", "evidence": "assumption-check"},
                  {"category": "scope", "question": "Only the current filtered view?", "evidence": "q1"}
                ]
                """;

        java.util.List<ai.pdlc.core.domain.GrillQuestion> followUps = PoAgent.parseFollowUps(output, 1);

        assertThat(followUps).extracting(ai.pdlc.core.domain.GrillQuestion::id).containsExactly("po2");
    }
    // -- revise() consumer contract (branch resolution, baseline fallback, intake context) --------

    private static final String CURRENT_PROPOSAL = """
            # Export the filtered orders view to CSV

            ## Story
            As a sales admin,
            I want to export exactly the filtered orders view to CSV,
            So that I can share order data with external stakeholders.

            ## Acceptance Criteria
            Scenario: export-current-view
              Given the sales admin has filtered the orders grid to 500 rows
              When  the user clicks Export CSV
              Then  a CSV file downloads with exactly 500 rows
            Scenario: rate-limit
              Given 20/hour for admin exports in the last hour
              When  the user requests the next export
              Then  the request returns HTTP 429 with Retry-After
            Scenario: audit
              Given the sales admin exports the filtered view
              When  the export completes
              Then  an audit row is written with user, filter hash, and row count

            ## Requirements
            ### Non-Functional requirements
            - performance: 10k rows < 5 s (p95)

            ### Out of Scope
            - scheduled exports (parked q7)
            """;

    private static final String STALE_MAIN_PROPOSAL = """
            # STALE main proposal DO-NOT-USE
            Scenario: stale-only-scenario
              GIVEN stale
              WHEN  stale
              THEN  stale
            """;

    private static final String RETURNED_REVISED = """
            # Export the filtered orders view to CSV

            ## Acceptance Criteria
            Scenario: export-current-view
              Given the sales admin has filtered the orders grid to 500 rows
              When  the user clicks Export CSV
              Then  a CSV file downloads with exactly 500 rows
            Scenario: rate-limit
              Given 30/hour for admin exports in the last hour
              When  the user requests the next export
              Then  the request returns HTTP 429 with Retry-After
            Scenario: audit
              Given the sales admin exports the filtered view
              When  the export completes
              Then  an audit row is written with user, filter hash, and row count
            """;

    private static Profile profile(String defaultBranch) {
        BoardConfig board = new BoardConfig("inmemory", "org", "proj", Map.of(), Map.of(),
                new BoardConfig.AuthConfig("none", null));
        RepoConfig repo = new RepoConfig("local-git", "url", defaultBranch, "openspec");
        NotifyConfig notify = new NotifyConfig("stub", "chan");
        AgentsConfig agents = new AgentsConfig(null, null, Map.of());
        return new Profile("local", board, repo, notify, agents, Map.of());
    }

    private static PoHandoff previous(String change, String parent) {
        Handoff env = new Handoff("po-agent", "plan-agent", "4412", CanonicalState.AWAITING_G1,
                List.of(), 0.8, List.of(), List.of());
        return new PoHandoff(env, parent, change, List.of("export-current-view"), Map.of(),
                List.of("orders"), Map.of("I", "pass"), List.of(), Map.of());
    }

    @Test
    void reviseReadsConfiguredBranchProposalNotStaleMainAndCarriesFeedbackFeatureAndGrillContext() {
        Ai ai = mock(Ai.class);
        PromptRunner runner = mock(PromptRunner.class);
        BoardPort board = mock(BoardPort.class);
        RepoPort repo = mock(RepoPort.class);
        when(ai.withDefaultLlm()).thenReturn(runner);
        when(runner.generateText(anyString())).thenReturn(RETURNED_REVISED);
        // The stale copy lives on "main"; the configured local profile writes to "restaurant-base".
        when(repo.readFile(eq("main"), anyString())).thenReturn(STALE_MAIN_PROPOSAL);
        when(repo.readFile(eq("restaurant-base"), anyString())).thenReturn(CURRENT_PROPOSAL);
        WorkItem feature = new WorkItem("4400", "feature", "Export orders feature",
                "Feature-level description: allow admins to export orders.", CanonicalState.NEW, null, "orders", List.of());
        when(board.getItem(new WorkItemRef("local", "4400"))).thenReturn(feature);

        PoAgent agent = new PoAgent(ai, board, repo, new PromptTemplates(profile("restaurant-base")), profile("restaurant-base"));
        WorkItemRef item = new WorkItemRef("local", "4412");
        List<Comment> comments = List.of(
                new Comment("c1", "lead@acme", "SquadLead", "story", "line:46", "20/hour for admin",
                        Comment.Intent.CHANGE, false, 1),
                new Comment("c2", "lead@acme", "SquadLead", "story", "scenario:audit", "looks good, ship it",
                        Comment.Intent.NOTE, false, 1));

        StoryDraft draft = agent.revise(item, previous("openspec/changes/export-orders-csv", "4400"),
                comments, DemoFixtures.grill());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).generateText(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        // The whole current proposal is the baseline; the stale main copy never reaches the model.
        assertThat(prompt).contains(CURRENT_PROPOSAL);
        assertThat(prompt).doesNotContain("DO-NOT-USE");
        assertThat(prompt).doesNotContain("stale-only-scenario");
        // Every explicitly-submitted comment reaches revision, intent-tagged (change and note alike).
        assertThat(prompt).contains("[change] line:46: 20/hour for admin");
        assertThat(prompt).contains("[note] scenario:audit: looks good, ship it");
        // Original feature description and resolved grill Q&A are supplied as supporting context.
        assertThat(prompt).contains("Feature-level description: allow admins to export orders.");
        assertThat(prompt).contains("Answer q1 (scope): Current filtered view, max 10k rows.");
        assertThat(prompt).contains("Parked (out of scope): q2, q3, q5, q6");

        // The model's revised story flows through to the draft, retaining the change path.
        assertThat(draft.storyMarkdown()).contains("export-current-view").contains("audit");
        assertThat(draft.specDeltaFiles()).isNotEmpty();
        assertThat(draft.handoff().change()).isEqualTo("openspec/changes/export-orders-csv");
    }

    @Test
    void reviseFallsBackToBoardStoryDescriptionWhenRepositoryBaselineMissing() {
        Ai ai = mock(Ai.class);
        PromptRunner runner = mock(PromptRunner.class);
        BoardPort board = mock(BoardPort.class);
        RepoPort repo = mock(RepoPort.class);
        when(ai.withDefaultLlm()).thenReturn(runner);
        when(runner.generateText(anyString())).thenReturn(RETURNED_REVISED);
        when(repo.readFile(anyString(), anyString())).thenReturn(null); // repo copy unavailable
        WorkItem storyItem = new WorkItem("4412", "story", "Export CSV story", CURRENT_PROPOSAL,
                CanonicalState.AWAITING_G1, "4400", "orders", List.of());
        WorkItemRef item = new WorkItemRef("local", "4412");
        when(board.getItem(item)).thenReturn(storyItem);

        PoAgent agent = new PoAgent(ai, board, repo, new PromptTemplates(profile("restaurant-base")), profile("restaurant-base"));
        StoryDraft draft = agent.revise(item, previous("openspec/changes/export-orders-csv", null),
                List.of(new Comment("c1", "lead@acme", "SquadLead", "story", "line:46", "20/hour for admin",
                        Comment.Intent.CHANGE, false, 1)), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).generateText(promptCaptor.capture());
        // The complete board description is used as the baseline, not just the board title.
        assertThat(promptCaptor.getValue()).contains(CURRENT_PROPOSAL);
        assertThat(promptCaptor.getValue()).doesNotContain("Export CSV story");
        assertThat(draft.handoff().change()).isEqualTo("openspec/changes/export-orders-csv");
    }

    @Test
    void reviseThrowsAndNeverCallsModelWhenNoBaselineIsAvailable() {
        Ai ai = mock(Ai.class);
        BoardPort board = mock(BoardPort.class);
        RepoPort repo = mock(RepoPort.class);
        when(repo.readFile(anyString(), anyString())).thenReturn(null);
        WorkItemRef item = new WorkItemRef("local", "4412");
        when(board.getItem(item)).thenReturn(null); // no repo copy, no board description

        PoAgent agent = new PoAgent(ai, board, repo, new PromptTemplates(profile("restaurant-base")), profile("restaurant-base"));

        assertThatThrownBy(() -> agent.revise(item, previous("openspec/changes/export-orders-csv", null),
                List.of(new Comment("c1", "lead@acme", "SquadLead", "story", "line:46", "change it",
                        Comment.Intent.CHANGE, false, 1)), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("4412")
                .hasMessageContaining("previous story content is unavailable");
        verifyNoInteractions(ai);
    }

    @Test
    void secondRevisionReadsUpdatedProposalSoAnEarlierAcceptedEditSurvives() {
        Ai ai = mock(Ai.class);
        PromptRunner runner = mock(PromptRunner.class);
        BoardPort board = mock(BoardPort.class);
        RepoPort repo = mock(RepoPort.class);
        when(ai.withDefaultLlm()).thenReturn(runner);
        when(runner.generateText(anyString())).thenReturn(RETURNED_REVISED);
        String proposalV1 = CURRENT_PROPOSAL;
        String proposalV2 = CURRENT_PROPOSAL + "\n- EARLIER-EDIT-SURVIVES: admin threshold raised to 30/hour\n";
        when(repo.readFile(eq("restaurant-base"), anyString())).thenReturn(proposalV1, proposalV2);

        PoAgent agent = new PoAgent(ai, board, repo, new PromptTemplates(profile("restaurant-base")), profile("restaurant-base"));
        WorkItemRef item = new WorkItemRef("local", "4412");
        List<Comment> feedback = List.of(new Comment("c1", "lead@acme", "SquadLead", "story", "line:46",
                "raise admin limit", Comment.Intent.CHANGE, false, 1));

        agent.revise(item, previous("openspec/changes/export-orders-csv", null), feedback, null);
        agent.revise(item, previous("openspec/changes/export-orders-csv", null), feedback, null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner, org.mockito.Mockito.times(2)).generateText(promptCaptor.capture());
        List<String> prompts = promptCaptor.getAllValues();
        assertThat(prompts.get(0)).doesNotContain("EARLIER-EDIT-SURVIVES");
        // The second revision reads the newest published proposal, so the earlier accepted edit is
        // carried into the next model input rather than reverting to the first version.
        assertThat(prompts.get(1)).contains("EARLIER-EDIT-SURVIVES");
    }

    @Test
    void reviseSurvivesFeatureReadFailureAndNullGrillWithValidBaseline() {
        Ai ai = mock(Ai.class);
        PromptRunner runner = mock(PromptRunner.class);
        BoardPort board = mock(BoardPort.class);
        RepoPort repo = mock(RepoPort.class);
        when(ai.withDefaultLlm()).thenReturn(runner);
        when(runner.generateText(anyString())).thenReturn(RETURNED_REVISED);
        when(repo.readFile(eq("restaurant-base"), anyString())).thenReturn(CURRENT_PROPOSAL);
        when(board.getItem(new WorkItemRef("local", "4400"))).thenThrow(new RuntimeException("board down"));

        PoAgent agent = new PoAgent(ai, board, repo, new PromptTemplates(profile("restaurant-base")), profile("restaurant-base"));
        WorkItemRef item = new WorkItemRef("local", "4412");

        // Optional feature read fails and the legacy grill is null; the valid current-story baseline
        // must still drive a normal revision (no throw, model still called with the baseline).
        StoryDraft draft = agent.revise(item, previous("openspec/changes/export-orders-csv", "4400"),
                List.of(new Comment("c1", "lead@acme", "SquadLead", "story", "line:46", "raise admin limit",
                        Comment.Intent.CHANGE, false, 1)), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).generateText(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains(CURRENT_PROPOSAL);
        assertThat(draft.handoff().change()).isEqualTo("openspec/changes/export-orders-csv");
    }

    @Test
    void temporalConverterDecodesQueuedThreeArgRevisionWithNullTrailingGrill() throws Exception {
        DataConverter converter = DataConverter.getDefaultInstance();
        WorkItemRef item = new WorkItemRef("local", "4412");
        PoHandoff previous = previous("openspec/changes/export-orders-csv", "4400");
        List<Comment> comments = List.of(new Comment("c1", "lead@acme", "SquadLead", "story", "line:46",
                "20/hour for admin", Comment.Intent.CHANGE, false, 1));

        // An already-queued activity input carries only the three original payloads.
        Optional<Payloads> payloads = converter.toPayloads(item, previous, comments);
        Method poRevise = AgentActivities.class.getMethod("poRevise", WorkItemRef.class, PoHandoff.class,
                List.class, GrillHandoff.class);
        Object[] decoded = converter.fromPayloads(payloads, poRevise.getParameterTypes(),
                poRevise.getGenericParameterTypes());

        assertThat(decoded).hasSize(4);
        assertThat(decoded[0]).isEqualTo(item);
        assertThat(decoded[1]).isEqualTo(previous);
        assertThat(decoded[2]).isEqualTo(comments);
        assertThat(decoded[3]).isNull(); // the new trailing grill defaults to null for old inputs
    }

    @Test
    void appendOutOfScopeInsertsIntoExistingMidDocumentSectionBeforeAcceptanceCriteria() {
        GrillHandoff grill = new GrillHandoff(
                new Handoff("grill-agent", "po-agent", "4412", CanonicalState.READY_FOR_STORY,
                        List.of(), 0.8, List.of(), List.of()),
                "story",
                List.of(new ai.pdlc.core.domain.GrillQuestion("q9", ai.pdlc.core.domain.GrillQuestion.Category.NFR,
                        "Should exports support PDF too?", "assumption-check",
                        ai.pdlc.core.domain.GrillQuestion.Status.PARKED, null, null)),
                List.of("q9"), List.of());
        String story = """
                # Title

                ## Requirements
                ### Out of Scope
                - existing item

                ## Acceptance Criteria
                Scenario: export
                  Given a precondition
                  When an action
                  Then a result
                """;

        String appended = PoAgent.appendOutOfScope(story, grill);

        int outOfScopeIndex = appended.indexOf("### Out of Scope");
        int acceptanceIndex = appended.indexOf("## Acceptance Criteria");
        int parkedIndex = appended.indexOf("(parked q9)");
        assertThat(outOfScopeIndex).isGreaterThanOrEqualTo(0);
        assertThat(parkedIndex).isGreaterThan(outOfScopeIndex).isLessThan(acceptanceIndex);
        assertThat(appended).contains("- existing item");
    }

    @Test
    void appendOutOfScopeCreatesSectionAtEndWhenAbsent() {
        GrillHandoff grill = new GrillHandoff(
                new Handoff("grill-agent", "po-agent", "4412", CanonicalState.READY_FOR_STORY,
                        List.of(), 0.8, List.of(), List.of()),
                "story",
                List.of(new ai.pdlc.core.domain.GrillQuestion("q9", ai.pdlc.core.domain.GrillQuestion.Category.NFR,
                        "Should exports support PDF too?", "assumption-check",
                        ai.pdlc.core.domain.GrillQuestion.Status.PARKED, null, null)),
                List.of("q9"), List.of());
        String story = """
                # Title

                ## Acceptance Criteria
                Scenario: export
                  Given a precondition
                  When an action
                  Then a result
                """;

        String appended = PoAgent.appendOutOfScope(story, grill);

        assertThat(appended).contains("### Out of Scope").contains("(parked q9)");
        assertThat(appended.indexOf("### Out of Scope")).isGreaterThan(appended.indexOf("## Acceptance Criteria"));
    }

}
