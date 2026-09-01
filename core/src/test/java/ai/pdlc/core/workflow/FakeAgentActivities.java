package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.ReleaseDocument;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewFinding;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.RolloutPlan;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process fake of the reasoning activities (no LLM calls) for {@link FeatureWorkflowImplTest}.
 * When {@code startWithOpenQuestion} is true, the first {@code grillEvaluate} call returns one
 * {@code open} question that only resolves once a matching board comment arrives — exercising the
 * stale-escalation timer branch.
 */
class FakeAgentActivities implements AgentActivities {

    final List<WorkItemRef> grillCalls = new CopyOnWriteArrayList<>();
    final List<WorkItemRef> reviseCalls = new CopyOnWriteArrayList<>();
    boolean startWithOpenQuestion = false;
    boolean returnBlockerFinding = false;
    boolean returnMonitorTrip = false;

    @Override
    public GrillHandoff grillEvaluate(WorkItemRef item, GrillHandoff previous, List<BoardCommentEvent> newComments) {
        grillCalls.add(item);
        Handoff envelope = new Handoff("grill-agent", "po-agent", item.boardId(),
                ai.pdlc.core.domain.CanonicalState.NEEDS_CLARIFICATION, List.of("ado:" + item.boardId()), 0.8, List.of(), List.of());

        if (!startWithOpenQuestion) {
            return new GrillHandoff(envelope, "story", List.of(
                    new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "Which orders?", "evidence", GrillQuestion.Status.ANSWERED, "current filtered view", "PO")
            ), List.of(), List.of());
        }

        if (previous == null) {
            GrillQuestion open = new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "Which orders?", "evidence", GrillQuestion.Status.OPEN, null, null);
            return new GrillHandoff(envelope, "story", List.of(open), List.of(), List.of());
        }

        // Re-run: resolve q1 if any new comment arrived.
        List<GrillQuestion> updated = new ArrayList<>();
        for (GrillQuestion q : previous.questions()) {
            if (q.status() == GrillQuestion.Status.OPEN && !newComments.isEmpty()) {
                updated.add(q.withAnswer(newComments.get(newComments.size() - 1).text(), "PO"));
            } else {
                updated.add(q);
            }
        }
        return new GrillHandoff(envelope, previous.typeDecision(), updated, previous.parked(), previous.constraintsHit());
    }

    @Override
    public StoryDraft poDraft(WorkItemRef item, GrillHandoff grill) {
        Handoff envelope = new Handoff("po-agent", "plan-agent", item.boardId(),
                ai.pdlc.core.domain.CanonicalState.AWAITING_G1, List.of(), 0.9, List.of(), List.of());
        PoHandoff handoff = new PoHandoff(envelope, item.boardId(), "openspec/changes/export-orders-csv",
                List.of("export-current-view", "rate-limit"), java.util.Map.of(), List.of("orders-service/export"),
                java.util.Map.of("I", "pass", "N", "pass", "V", "pass", "E", "pass", "S", "pass", "T", "pass"),
                List.of(), java.util.Map.of());
        String story = "# Export the filtered orders view to CSV\n\n## Acceptance criteria\nScenario: rate limit\n  GIVEN 10 exports in the last hour\n  WHEN the 11th export happens\n  THEN the next returns 429\n";
        return new StoryDraft(handoff, story, java.util.Map.of("specs/orders/spec.md", "ADDED rate-limit requirement"));
    }

    @Override
    public StoryDraft poRevise(WorkItemRef item, PoHandoff previous, List<Comment> openComments) {
        reviseCalls.add(item);
        String story = "# Export the filtered orders view to CSV\n\n## Acceptance criteria\nScenario: rate limit\n  GIVEN 10 exports (20 for admin) in the last hour\n  WHEN the 11th export happens\n  THEN the next returns 429\n";
        return new StoryDraft(previous, story, java.util.Map.of("specs/orders/spec.md", "ADDED rate-limit requirement now has two thresholds"));
    }

    @Override
    public PlanHandoff planTasks(WorkItemRef story, PoHandoff po) {
        Handoff envelope = new Handoff("plan-agent", "build-worker", story.boardId(),
                ai.pdlc.core.domain.CanonicalState.PLANNED, List.of(), 0.9, List.of(), List.of());
        Task t1 = new Task("T1", "Implement rate-limit", "orders-service/export", "rate-limit",
                List.of("src/export.js"), "test/export.test.js",
                new Task.TaskBudget(6, 120_000L, Duration.ofMinutes(10)), List.of());
        return new PlanHandoff(envelope, List.of(t1), List.of(List.of("T1")));
    }

    @Override
    public ReviewHandoff reviewStory(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results) {
        Handoff envelope = new Handoff("review-agent", "gate-2", story.boardId(),
                ai.pdlc.core.domain.CanonicalState.AWAITING_G2, List.of(), 0.9, List.of(), List.of());
        List<ReviewFinding> findings = returnBlockerFinding
                ? List.of(new ReviewFinding(ReviewFinding.Severity.BLOCKER, "scope", "T1 edited a forbidden file", null))
                : List.of();
        return new ReviewHandoff(envelope, List.of(), findings);
    }

    @Override
    public ReleaseHandoff draftReleasePack(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results, ReviewHandoff review) {
        Handoff envelope = new Handoff("release-agent", "monitor-agent", story.boardId(),
                ai.pdlc.core.domain.CanonicalState.AWAITING_G3, List.of(), 0.9, List.of(), List.of());
        List<ReleaseDocument> documents = List.of(
                new ReleaseDocument("change-notes", "Change notes", "content", "PO"),
                new ReleaseDocument("rollout-plan", "Rollout & rollback plan", "content", "SquadLead"),
                new ReleaseDocument("monitor-rules", "Monitor rules", "content", "QA"),
                new ReleaseDocument("test-evidence", "Test evidence", "content", "QA"));
        RolloutPlan rollout = new RolloutPlan(10, 60, "orders.export_csv", "flag off + deploy prev");
        List<MonitorRule> rules = List.of(
                new MonitorRule("export-error-rate", "http_5xx_rate", "> 2% over 15m", "file-card", "PO"));
        return new ReleaseHandoff(envelope, "R-test-01", documents, rollout, rules);
    }

    @Override
    public MonitorHandoff evaluateMonitorRules(WorkItemRef story, List<MonitorRule> rules) {
        Handoff envelope = new Handoff("monitor-agent", "grill-agent", story.boardId(),
                ai.pdlc.core.domain.CanonicalState.DONE, List.of(), 0.9, List.of(), List.of());
        List<MonitorHandoff.Trip> trips = returnMonitorTrip
                ? List.of(new MonitorHandoff.Trip(rules.isEmpty() ? "none" : rules.get(0).id(), "evidence", "bug", "PO"))
                : List.of();
        return new MonitorHandoff(envelope, trips);
    }
}
