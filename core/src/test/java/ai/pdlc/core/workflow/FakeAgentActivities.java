package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.AgentMentionRequest;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.GrillRound;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.ReleaseDocument;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewFinding;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.RolloutPlan;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process fake of the reasoning activities (no LLM calls) for {@link FeatureWorkflowImplTest}.
 * When {@code startWithOpenQuestion} is true, the first {@code grillEvaluate} call returns one
 * {@code open} question that only resolves once a matching board comment arrives — exercising the
 * stale-escalation timer branch. When {@code storyCount} is 2, {@code poDraft} returns two distinct
 * stories (simulating a PO agent actor/factor split). {@code failStoryQualityOnce} makes the first
 * {@code evaluateQuality("story", …)} call FAIL (auto-revise round exercised), every later call —
 * including the auto-revised one — PASS.
 */
class FakeAgentActivities implements AgentActivities {

    final List<WorkItemRef> grillCalls = new CopyOnWriteArrayList<>();
    final List<WorkItemRef> reviseCalls = new CopyOnWriteArrayList<>();
    final List<List<Comment>> reviseComments = new CopyOnWriteArrayList<>();
    final List<GrillHandoff> reviseGrillHandoffs = new CopyOnWriteArrayList<>();
    final List<String> qualityCalls = new CopyOnWriteArrayList<>();
    boolean startWithOpenQuestion = false;
    boolean returnBlockerFinding = false;
    boolean returnMonitorTrip = false;
    int storyCount = 1;
    boolean failStoryQualityOnce = false;
    /** When {@code > 0}, the first this-many {@code allowFollowUps} {@code poDraft} calls return a
     * follow-up question ({@code po1}, {@code po2}, …) instead of drafting. */
    int poFollowUpRounds = 0;
    final List<Boolean> poDraftCalls = new CopyOnWriteArrayList<>();
    final List<String> qualityContents = new CopyOnWriteArrayList<>();
    /** Optional test-owned latches to hold {@link #poRevise} open for a race test: counted down on
     * entry, then awaited before returning, when non-null. Always release in a {@code finally} -
     * never rely on a timeout to unblock the activity thread. */
    java.util.concurrent.CountDownLatch reviseStarted;
    java.util.concurrent.CountDownLatch reviseRelease;
    /** Optional test-owned latch that holds every {@link #grillNextRound} call with a non-null
     * {@code previous} (i.e. every round after the first) open until counted down — lets a test
     * observe {@code grill()} between the last fold and the next posted round. */
    java.util.concurrent.CountDownLatch nextRoundRelease;
    private boolean storyQualityFailedOnce = false;

    @Override
    public GrillHandoff grillEvaluate(WorkItemRef item, GrillHandoff previous, List<BoardCommentEvent> newComments) {
        grillCalls.add(item);
        Handoff envelope = new Handoff("grill-agent", "po-agent", item.boardId(),
                ai.pdlc.core.domain.CanonicalState.NEEDS_CLARIFICATION, List.of("ado:" + item.boardId()), 0.8, List.of(), List.of());

        if (previous == null) {
            if (!startWithOpenQuestion) {
                return new GrillHandoff(envelope, "story", List.of(
                        new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "Which orders?", "evidence", GrillQuestion.Status.ANSWERED, "current filtered view", "PO")
                ), List.of(), List.of());
            }
            GrillQuestion open = new GrillQuestion("q1", GrillQuestion.Category.SCOPE, "Which orders?", "evidence", GrillQuestion.Status.OPEN, null, null);
            return new GrillHandoff(envelope, "story", List.of(open), List.of(), List.of());
        }

        // Re-run: resolve every OPEN question (grill or PO follow-up) if any new comment arrived -
        // mirrors GrillAgent.evaluateAnswers closely enough for FeatureWorkflowImplTest's purposes
        // without parsing per-id markers.
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

    final List<WorkItemRef> nextRoundCalls = new CopyOnWriteArrayList<>();
    /** Configurable adaptive-round frontiers, consumed in order (after the initial
     * {@code startWithOpenQuestion} round, if any); each entry is the batch of NEW questions for
     * that round — ids are auto-assigned via {@link GrillQuestion#nextGrillId}, mirroring the real
     * agent's contract, so tests only specify category/question/evidence. Exhausted (or empty, by
     * default) => an empty frontier plus {@link #nextRoundSummary}, matching a real completed
     * interview — most tests need only send the one resulting confirmation reply. */
    final List<List<GrillQuestion>> nextRoundFrontiers = new CopyOnWriteArrayList<>();
    String nextRoundSummary = "All decisions settled.";
    private int nextRoundCallIndex = 0;

    @Override
    public GrillRound grillNextRound(WorkItemRef item, GrillHandoff previous) {
        nextRoundCalls.add(item);
        if (previous != null && !previous.allQuestionsResolved()) {
            throw new IllegalStateException("grillNextRound called with unresolved previous for " + item);
        }
        if (previous != null && nextRoundRelease != null) {
            try {
                nextRoundRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while holding grillNextRound", e);
            }
        }
        List<GrillQuestion> history = previous == null ? new ArrayList<>() : new ArrayList<>(previous.questions());
        Handoff envelope = previous != null ? previous.envelope()
                : new Handoff("grill-agent", "po-agent", item.boardId(),
                        ai.pdlc.core.domain.CanonicalState.NEEDS_CLARIFICATION, List.of("ado:" + item.boardId()), 0.8, List.of(), List.of());
        String typeDecision = previous != null ? previous.typeDecision() : "story";
        List<String> parked = previous == null ? List.of() : previous.parked();
        List<String> constraintsHit = previous == null ? List.of() : previous.constraintsHit();

        int callIndex = nextRoundCallIndex++;
        List<GrillQuestion> newBatch = null;
        if (callIndex == 0 && startWithOpenQuestion) {
            newBatch = List.of(new GrillQuestion(null, GrillQuestion.Category.SCOPE, "Which orders?", "evidence", GrillQuestion.Status.OPEN, null, null));
        } else {
            int frontierIndex = startWithOpenQuestion ? callIndex - 1 : callIndex;
            if (frontierIndex >= 0 && frontierIndex < nextRoundFrontiers.size()) {
                newBatch = nextRoundFrontiers.get(frontierIndex);
            }
        }

        if (newBatch != null && !newBatch.isEmpty()) {
            for (GrillQuestion q : newBatch) {
                String id = GrillQuestion.nextGrillId(history);
                history.add(new GrillQuestion(id, q.category(), q.question(), q.evidence(), GrillQuestion.Status.OPEN, null, null));
            }
            return new GrillRound(new GrillHandoff(envelope, typeDecision, history, parked, constraintsHit), null);
        }
        return new GrillRound(new GrillHandoff(envelope, typeDecision, history, parked, constraintsHit), nextRoundSummary);
    }

    @Override
    public PoDraftResult poDraft(WorkItemRef item, GrillHandoff grill, boolean allowFollowUps) {
        poDraftCalls.add(allowFollowUps);
        if (allowFollowUps && poDraftCalls.size() <= poFollowUpRounds) {
            return new PoDraftResult(List.of(), List.of(new GrillQuestion("po" + poDraftCalls.size(),
                    GrillQuestion.Category.USERS, "Which roles may export?", GrillQuestion.ASSUMPTION_CHECK,
                    GrillQuestion.Status.OPEN, null, null)));
        }
        List<StoryDraft> drafts = new ArrayList<>();
        for (int i = 0; i < storyCount; i++) {
            String suffix = i == 0 ? "" : "-b";
            Handoff envelope = new Handoff("po-agent", "plan-agent", item.boardId(),
                    ai.pdlc.core.domain.CanonicalState.AWAITING_G1, List.of(), 0.9, List.of(), List.of());
            PoHandoff handoff = new PoHandoff(envelope, item.boardId(), "openspec/changes/export-orders-csv" + suffix,
                    List.of("export-current-view", "rate-limit"), java.util.Map.of(), List.of("orders-service/export"),
                    java.util.Map.of("I", "pass", "N", "pass", "V", "pass", "E", "pass", "S", "pass", "T", "pass"),
                    List.of(), java.util.Map.of());
            String story = "# Export the filtered orders view to CSV" + suffix
                    + "\n\n## Acceptance criteria\nScenario: rate limit\n  GIVEN 10 exports in the last hour\n  WHEN the 11th export happens\n  THEN the next returns 429\n";
            drafts.add(new StoryDraft(handoff, story, java.util.Map.of("specs/orders/spec.md", "ADDED rate-limit requirement")));
        }
        return new PoDraftResult(drafts, List.of());
    }

    static final String REVISED_STORY = "# Export the filtered orders view to CSV\n\n## Acceptance criteria\nScenario: rate limit\n  GIVEN 10 exports (20 for admin) in the last hour\n  WHEN the 11th export happens\n  THEN the next returns 429\n";

    @Override
    public StoryDraft poRevise(WorkItemRef item, PoHandoff previous, List<Comment> openComments, GrillHandoff grill) {
        reviseCalls.add(item);
        reviseComments.add(openComments);
        reviseGrillHandoffs.add(grill);
        if (reviseStarted != null) {
            reviseStarted.countDown();
        }
        if (reviseRelease != null) {
            try {
                reviseRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return new StoryDraft(previous, REVISED_STORY, java.util.Map.of("specs/orders/spec.md", "ADDED rate-limit requirement now has two thresholds"));
    }

    @Override
    public QualityReport evaluateQuality(WorkItemRef item, String subjectKind, String contentMd) {
        qualityCalls.add(subjectKind + ":" + item.boardId());
        qualityContents.add(contentMd);
        if ("story".equals(subjectKind) && failStoryQualityOnce && !storyQualityFailedOnce) {
            storyQualityFailedOnce = true;
            return new QualityReport("story", false, 40, List.of("missing NFR"), "VERDICT: FAIL");
        }
        return new QualityReport(subjectKind, true, 90, List.of(), "VERDICT: PASS");
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

    final List<List<Comment>> releaseFeedback = new CopyOnWriteArrayList<>();

    @Override
    public ReleaseHandoff draftReleasePack(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results, ReviewHandoff review, List<Comment> feedback) {
        releaseFeedback.add(feedback);
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

    @Override
    public String mentionAnalyze(AgentMentionRequest request) {
        return "fake mention result for " + request.agentName();
    }
}
