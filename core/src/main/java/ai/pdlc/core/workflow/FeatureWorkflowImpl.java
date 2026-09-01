package ai.pdlc.core.workflow;

import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.ReleaseDocument;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewFinding;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link FeatureWorkflow} implementation — orchestration-decision §6 Java sketch, truncated after
 * the monitor agent's one evaluation pass for the pilot (a true multi-day watch window is out of
 * scope — see {@link #run} step 12). Contains no LLM/HTTP calls and no wall-clock reads outside
 * {@link Workflow}; all I/O lives in {@link AgentActivities}, {@link BoardSideEffects} and
 * {@link BuildActivities} (orchestration-decision §5 determinism rule).
 */
public class FeatureWorkflowImpl implements FeatureWorkflow {

    /** playbook §1 "Stops": 5 working days with open questions → tag Squad Lead, set stale. Modelled
     * as 5 calendar days for the pilot (a business-day timer needs a holiday calendar, out of scope). */
    static final Duration STALE_ESCALATION_TIMER = Duration.ofDays(5);

    /** The account that runs the maker (PO) agent; SoD forbids it from also being a checker. */
    static final String MAKER_BOT_IDENTITY = "po-agent-bot";

    /** Author identity for blocker findings the review agent seeds as blocking comments. */
    static final String REVIEW_BOT_IDENTITY = "review-agent-bot";

    /** Prefix for a release document's {@code Approval.stage()}/{@code Comment.stage()} — tech-stack
     * §3.1 "Gate 3 uses the same shape"; {@code Comment.stage()}'s own doc comment names this
     * literal ({@code release-pack:<doc-id>}). */
    static final String RELEASE_STAGE_PREFIX = "release-pack:";

    private static final ActivityOptions AGENT_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.REASONING)
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .build();

    private static final ActivityOptions BOARD_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.BOARD)
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .build();

    /** The build loop drives omp over ACP per task — generous timeout, heartbeats so a stuck or
     * killed build-worker process is detected well before StartToCloseTimeout. */
    private static final ActivityOptions BUILD_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(TaskQueues.BUILD)
            .setStartToCloseTimeout(Duration.ofMinutes(30))
            .setHeartbeatTimeout(Duration.ofMinutes(2))
            .build();

    private final AgentActivities agents = Workflow.newActivityStub(AgentActivities.class, AGENT_ACTIVITY_OPTIONS);
    private final BoardSideEffects board = Workflow.newActivityStub(BoardSideEffects.class, BOARD_ACTIVITY_OPTIONS);
    private final BuildActivities build = Workflow.newActivityStub(BuildActivities.class, BUILD_ACTIVITY_OPTIONS);

    private int version = 1;
    private final Map<String, Approval> approvals = new LinkedHashMap<>();
    /** Gate 3 only: document id → its signature. Documents need per-document checker roles (a
     * document can share a checker role with another document signed by a *different* physical
     * person), which a single role-keyed map cannot represent - see {@link #approveDocument}. */
    private final Map<String, Approval> documentSignatures = new LinkedHashMap<>();
    private final List<Comment> comments = new ArrayList<>();
    private final List<BoardCommentEvent> pendingBoardComments = new ArrayList<>();
    private CanonicalState stage = CanonicalState.NEW;

    private WorkItemRef storyRef;
    private PoHandoff currentPoHandoff;
    private ReleaseHandoff currentRelease;
    private GateConfig gate1;
    private GateConfig gate2;
    private GateConfig gate3;
    /** The gate currently governing {@link #sodAllows}/{@link #gateSatisfied} — gate 1 (story
     * review) until it passes, then gate 2 (PR review), then gate 3 (release pack); the same 4
     * signals drive every episode sequentially (tech-stack §3.1: every gate reuses this shape). */
    private GateConfig activeGate;

    @Override
    public void run(WorkItemRef item) {
        gate1 = board.loadGate1Config(item.profile());
        gate2 = board.loadGate2Config(item.profile());
        gate3 = board.loadGate3Config(item.profile());
        activeGate = gate1;

        // 1. Grill: loop until every question is answered/parked, escalating stale at the 5-day timer.
        GrillHandoff grill = agents.grillEvaluate(item, null, List.of());
        board.postGrillQuestions(item, grill);
        stage = CanonicalState.NEEDS_CLARIFICATION;
        while (!grill.allQuestionsResolved()) {
            boolean gotComments = Workflow.await(STALE_ESCALATION_TIMER, () -> !pendingBoardComments.isEmpty());
            if (!gotComments) {
                board.escalateStale(item);
                stage = CanonicalState.STALE;
                Workflow.await(() -> !pendingBoardComments.isEmpty());
            }
            List<BoardCommentEvent> newComments = List.copyOf(pendingBoardComments);
            pendingBoardComments.clear();
            grill = agents.grillEvaluate(item, grill, newComments);
        }

        // 2. All answered/parked -> ready-for-story, attach grill.md.
        board.transitionReadyForStory(item, grill);
        stage = CanonicalState.READY_FOR_STORY;

        // 3. Draft the story + spec delta; create the child story item; awaiting-G1.
        StoryDraft draft = agents.poDraft(item, grill);
        PublishResult published = board.publishStory(item, draft);
        storyRef = new WorkItemRef(item.profile(), published.storyBoardId());
        currentPoHandoff = draft.handoff();
        version = published.version();
        stage = CanonicalState.AWAITING_G1;

        // 4 + 5. Signal handlers (comment/approve/requestChanges/commentAdded) drive state below;
        // wait for gate 1: two named, distinct-identity approvals on the current version, no open
        // blocking comments.
        Workflow.await(this::gateSatisfied);
        board.transitionApproved(storyRef, version, 1);
        stage = CanonicalState.APPROVED;

        // 6. Plan: deterministic task breakdown from the spec delta's ADDED/MODIFIED scenarios.
        PlanHandoff plan = agents.planTasks(storyRef, currentPoHandoff);
        String defaultBranch = board.publishTasks(storyRef, plan);
        stage = CanonicalState.PLANNED;

        // 7. Build loop: omp over ACP, one shared story branch, wave by wave (a wave only starts
        // once every earlier wave's tasks have returned).
        String branch = "story/" + storyRef.boardId();
        board.transitionInProgress(storyRef);
        stage = CanonicalState.IN_PROGRESS;
        Map<String, Task> tasksById = new LinkedHashMap<>();
        for (Task t : plan.tasks()) {
            tasksById.put(t.id(), t);
        }
        List<BuildResult> results = new ArrayList<>();
        for (List<String> wave : plan.waves()) {
            List<Promise<BuildResult>> pending = new ArrayList<>();
            for (String taskId : wave) {
                Task task = tasksById.get(taskId);
                pending.add(Async.function(build::runTask, storyRef, task, branch, defaultBranch));
            }
            for (Promise<BuildResult> p : pending) {
                results.add(p.get());
            }
        }

        // 8. Review the accumulated diff once, open the PR, post findings; awaiting-G2.
        ReviewHandoff review = agents.reviewStory(storyRef, currentPoHandoff, plan.tasks(), results);
        board.openStoryPr(storyRef, branch, plan.tasks(), results, review);
        stage = CanonicalState.AWAITING_G2;

        // 9. Gate 2: reuse the same approve/comment/requestChanges signal surface, now scoped to
        // gate 2's roles (FSDeveloper, QA) and a fresh version/approval episode. Blocker findings
        // from the review agent seed the open-blocking-comments set so gate 2 cannot pass while
        // any remain open - the same mechanism gate 1 uses for human blocking comments
        // (ReviewHandoff#hasBlockers()'s contract, otherwise unenforced).
        activeGate = gate2;
        version = 1;
        approvals.clear();
        comments.clear();
        int findingIndex = 0;
        for (ReviewFinding finding : review.findings()) {
            if (finding.severity() == ReviewFinding.Severity.BLOCKER) {
                comments.add(new Comment("finding-" + findingIndex++, REVIEW_BOT_IDENTITY, "review-agent", "pr",
                        finding.file() != null ? finding.file() : finding.category(), finding.message(),
                        Comment.Intent.CHANGE, true, version));
            }
        }
        Workflow.await(this::gateSatisfied);
        board.transitionApproved(storyRef, version, 2);
        stage = CanonicalState.APPROVED;

        // 10. Release agent drafts the pack (playbook §7); publish it, awaiting-G3.
        ReleaseHandoff release = agents.draftReleasePack(storyRef, currentPoHandoff, plan.tasks(), results, review);
        board.publishReleasePack(storyRef, release);
        currentRelease = release;
        stage = CanonicalState.AWAITING_G3;

        // 11. Gate 3: a document-level "sign" is the same `approve` signal, `stage:
        // "release-pack:<doc-id>"`; gate 3 opens once every document has a signature from its own
        // named checker role (tech-stack §3.1, §3.4) - see gate3Satisfied/approveDocument.
        activeGate = gate3;
        version = 1;
        approvals.clear();
        documentSignatures.clear();
        comments.clear();
        Workflow.await(this::gateSatisfied);
        board.transitionApproved(storyRef, version, 3);
        stage = CanonicalState.APPROVED;

        // 12. Deploy (playbook §7 step 6), then one monitor evaluation pass (playbook §8 step 1-3).
        // A true multi-day watch window (the real default) is out of pilot scope; this proves the
        // rule-evaluation -> evidence -> filed-card mechanism once, deterministically, right after
        // deploy - a later phase would re-trigger this on a schedule instead of ending the workflow.
        board.deployRelease(storyRef, release, branch);
        stage = CanonicalState.DONE;
        MonitorHandoff monitorResult = agents.evaluateMonitorRules(storyRef, release.monitorRules());
        board.fileMonitorCards(storyRef, monitorResult);
    }

    private boolean gateSatisfied() {
        if (!openBlockingComments().isEmpty()) {
            return false;
        }
        if (stage == CanonicalState.AWAITING_G3) {
            return gate3Satisfied();
        }
        return activeGate.roles().stream().allMatch(approvals::containsKey);
    }

    private boolean gate3Satisfied() {
        if (currentRelease == null || currentRelease.documents().isEmpty()) {
            return false;
        }
        for (ReleaseDocument doc : currentRelease.documents()) {
            Approval signature = documentSignatures.get(doc.id());
            if (signature == null || !doc.checkerRole().equals(signature.role())) {
                return false;
            }
        }
        return true;
    }

    private List<Comment> openBlockingComments() {
        return comments.stream().filter(Comment::blocking).toList();
    }

    @Override
    public void comment(Comment c) {
        comments.add(c);
        if (c.blocking()) {
            approvals.clear();
            documentSignatures.clear();
        }
    }

    @Override
    public void approve(Approval a) {
        if (a.stage() != null && a.stage().startsWith(RELEASE_STAGE_PREFIX)) {
            approveDocument(a);
            return;
        }
        if (a.version() != version) {
            return; // stale approval ignored
        }
        if (!sodAllows(a)) {
            return; // maker != checker, role check, distinct identities
        }
        approvals.put(a.role(), a);
    }

    /** Gate 3's per-document signing: the checker role is fixed by the document, not by a shared
     * gate role list, and two documents may share a checker role while being signed by different
     * physical identities - so this validates against {@link ReleaseDocument#checkerRole()}
     * directly rather than reusing {@link #sodAllows}. */
    private void approveDocument(Approval a) {
        if (a.version() != version || currentRelease == null) {
            return;
        }
        String docId = a.stage().substring(RELEASE_STAGE_PREFIX.length());
        ReleaseDocument doc;
        try {
            doc = currentRelease.document(docId);
        } catch (IllegalArgumentException unknownDocument) {
            return;
        }
        if (!doc.checkerRole().equals(a.role()) || !gate3.roles().contains(a.role())) {
            return; // wrong checker role for this specific document
        }
        if (MAKER_BOT_IDENTITY.equals(a.who()) || REVIEW_BOT_IDENTITY.equals(a.who())) {
            return;
        }
        documentSignatures.put(docId, a);
    }

    @Override
    public void requestChanges(String by) {
        version++;
        approvals.clear();
        List<Comment> openComments = openBlockingComments();
        List<String> resolvedIds = openComments.stream().map(Comment::id).toList();
        if (stage == CanonicalState.AWAITING_G1) {
            StoryDraft revised = agents.poRevise(storyRef, currentPoHandoff, openComments);
            board.publishRevision(storyRef, version, revised, resolvedIds);
            currentPoHandoff = revised.handoff();
        }
        if (stage == CanonicalState.AWAITING_G3) {
            documentSignatures.clear(); // pack_version bump invalidates every signature (playbook §7 "Rules")
        }
        // Gate 2/3: the pilot does not wire an automatic rebuild/re-draft from review feedback -
        // requestChanges still bumps the version and clears approvals/signatures (blocking approval
        // on the stale version) so the signal contract stays identical across every gate; a human
        // pushes more commits / a future build-order phase re-invokes the agent before re-approving.
        comments.clear(); // every open comment is considered handled by this revision/version bump
    }

    @Override
    public void commentAdded(BoardCommentEvent e) {
        pendingBoardComments.add(e);
    }

    @Override
    public ReviewState state() {
        Map<String, Approval> currentApprovals = stage == CanonicalState.AWAITING_G3 ? documentSignatures : approvals;
        return new ReviewState(version, Map.copyOf(currentApprovals), openBlockingComments(), stage);
    }

    private boolean sodAllows(Approval a) {
        if (!activeGate.roles().contains(a.role())) {
            return false;
        }
        if (MAKER_BOT_IDENTITY.equals(a.who())) {
            return false;
        }
        for (Approval existing : approvals.values()) {
            if (existing.who().equals(a.who())) {
                return false; // same physical identity cannot hold two checker roles on this gate
            }
        }
        return true;
    }
}
