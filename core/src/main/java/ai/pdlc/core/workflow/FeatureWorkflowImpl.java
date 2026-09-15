package ai.pdlc.core.workflow;

import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.QualityReport;
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
import java.util.function.Function;

/**
 * {@link FeatureWorkflow} implementation — orchestration-decision §6 Java sketch, truncated after
 * the monitor agent's one evaluation pass for the pilot (a true multi-day watch window is out of
 * scope — see {@link #run} step 12). Contains no LLM/HTTP calls and no wall-clock reads outside
 * {@link Workflow}; all I/O lives in {@link AgentActivities}, {@link BoardSideEffects} and
 * {@link BuildActivities} (orchestration-decision §5 determinism rule).
 *
 * <p>When the PO agent splits a feature into multiple stories (by actor/factor), every story flows
 * through the full G1→plan→build→G2→G3 pipeline <em>sequentially</em> in this one workflow
 * execution (a user-settled decision: not child workflows) — see the per-story loop in {@link #run}
 * step 3-12. Story 0 starts {@code awaiting-G1} immediately; every later story is published
 * {@code queued} and only flips to {@code awaiting-G1} via {@link BoardSideEffects#activateStory}
 * once the previous story's episode completes.
 */
public class FeatureWorkflowImpl implements FeatureWorkflow {

    /** playbook §1 "Stops": 5 working days with open questions → tag Squad Lead, set stale. Modelled
     * as 5 calendar days for the pilot (a business-day timer needs a holiday calendar, out of scope). */
    static final Duration STALE_ESCALATION_TIMER = Duration.ofDays(5);

    /** The account that runs the maker (PO) agent; SoD forbids it from also being a checker. */
    static final String MAKER_BOT_IDENTITY = "po-agent-bot";

    /** Author identity for blocker findings the review agent seeds as blocking comments. */
    static final String REVIEW_BOT_IDENTITY = "review-agent-bot";

    /** Author identity for quality findings the quality agent seeds as (non-blocking) comments on
     * a failed story verdict — visible in the comment stream even though they don't block the gate
     * signal surface directly (the {@link #qualityPassed} flag does that instead). */
    static final String QUALITY_BOT_IDENTITY = "quality-agent-bot";

    /** Prefix for a release document's {@code Approval.stage()}/{@code Comment.stage()} — tech-stack
     * §3.1 "Gate 3 uses the same shape"; {@code Comment.stage()}'s own doc comment names this
     * literal ({@code release-pack:<doc-id>}). */
    static final String RELEASE_STAGE_PREFIX = "release-pack:";

    /** playbook §2: at most 2 PO agent follow-up rounds before it must draft regardless. */
    static final int MAX_PO_FOLLOW_UP_ROUNDS = 2;

    /** A review-agent blocker on a failing task sends the task back to the build loop once,
     * automatically, before gate 2 waits for a human; every human "Request changes" round after
     * that is uncapped, like gate 1. */
    static final int MAX_AUTO_FIX_ROUNDS = 1;

    /** A single task escalating to a human more than this many times (even after guidance) stops
     * asking again; the escalation flows into review as a SHOULD finding instead (today's
     * behavior), so a stuck task can never block the story forever. */
    static final int MAX_HUMAN_INPUT_ROUNDS_PER_TASK = 3;

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
    /** Current grill handoff, including any PO agent follow-ups and build-loop {@code h*} human-
     * input questions — exposed via {@link #grill()}. */
    private GrillHandoff grill;

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
    /** Set once the quality agent's story verdict passes (directly, or after up to 2 auto-revise
     * rounds, or after a human requestChanges cycle re-evaluates) — {@link #gateSatisfied} hard-
     * blocks gate 1 while this is false, independent of the approval/comment signal surface. */
    private boolean qualityPassed;

    /** Gate 2 only: a human "Request changes" while {@code AWAITING_G2}, captured by {@link
     * #requestChanges} and consumed by {@link #run}'s gate-2 loop to drive a fix round. */
    private record FixRequest(String by, List<Comment> comments) {
    }

    private FixRequest pendingFixRequest;
    /** Gate 3 only: set by {@link #requestChanges} while {@code AWAITING_G3}; consumed by {@link
     * #run}'s gate-3 loop to re-draft and republish the release pack. */
    private boolean pendingReleaseRedraft;
    private List<Comment> releaseFeedback = List.of();
    /** Set by {@link #fixRound} after every re-review, so the caller can pick up the fresh
     * {@link ReviewHandoff} without threading an extra return value through the gate-2 loop. */
    private ReviewHandoff lastReview;

    private int humanQuestionCounter = 0;
    /** task id → how many times a human has already answered an escalation for it, capped at
     * {@link #MAX_HUMAN_INPUT_ROUNDS_PER_TASK}. */
    private final Map<String, Integer> humanRoundsByTask = new LinkedHashMap<>();

    @Override
    public void run(WorkItemRef item) {
        gate1 = board.loadGate1Config(item.profile());
        gate2 = board.loadGate2Config(item.profile());
        gate3 = board.loadGate3Config(item.profile());
        activeGate = gate1;

        // 1. Grill: loop until every question is answered/parked, escalating stale at the 5-day timer.
        grill = agents.grillEvaluate(item, null, List.of());
        board.postGrillQuestions(item, grill);
        awaitClarification(item);

        // 2-3. Draft the story (or one story per actor/factor - PO agent split); the PO agent may
        // instead ask follow-up questions (id po1, po2, ...) up to MAX_PO_FOLLOW_UP_ROUNDS times,
        // each round re-entering the clarification loop, before it must draft regardless.
        int followUpRounds = 0;
        PoDraftResult drafted;
        while (true) {
            board.transitionReadyForStory(item, grill); // re-attaches grill.md with the new answers each round
            stage = CanonicalState.READY_FOR_STORY;
            drafted = agents.poDraft(item, grill, followUpRounds < MAX_PO_FOLLOW_UP_ROUNDS);
            if (!drafted.needsClarification()) {
                break;
            }
            followUpRounds++;
            grill = grill.withFollowUps(drafted.followUps());
            board.postFollowUpQuestions(item, grill);
            awaitClarification(item);
        }
        List<StoryDraft> drafts = drafted.drafts();
        List<PublishResult> published = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            published.add(board.publishStory(item, drafts.get(i), i, i > 0));
        }

        // 4-12. Sequential-in-one-workflow: each story runs the full G1->plan->build->G2->G3
        // pipeline in turn before the next story activates.
        for (int i = 0; i < drafts.size(); i++) {
            storyRef = new WorkItemRef(item.profile(), published.get(i).storyBoardId());
            currentPoHandoff = drafts.get(i).handoff();
            version = published.get(i).version();
            approvals.clear();
            documentSignatures.clear();
            comments.clear();
            currentRelease = null;
            activeGate = gate1;
            qualityPassed = false;
            if (i > 0) {
                board.activateStory(storyRef);
            }
            stage = CanonicalState.AWAITING_G1;

            // Quality gate (hard-blocks G1): evaluate, auto-revise up to 2 rounds on FAIL, then
            // fall through to the human requestChanges cycle (which re-evaluates - see below).
            String storyMd = drafts.get(i).storyMarkdown();
            QualityReport q = agents.evaluateQuality(storyRef, "story", storyMd);
            board.saveQualityReport(storyRef, version, q);
            int rounds = 0;
            while (!q.passed() && rounds < 2) {
                rounds++;
                version++;
                approvals.clear();
                List<Comment> qualityComments = new ArrayList<>();
                for (int fi = 0; fi < q.findings().size(); fi++) {
                    qualityComments.add(new Comment("quality-" + version + "-" + fi, QUALITY_BOT_IDENTITY,
                            "quality-agent", "story", "quality", q.findings().get(fi), Comment.Intent.CHANGE,
                            false, version));
                }
                StoryDraft revised = agents.poRevise(storyRef, currentPoHandoff, qualityComments);
                board.publishRevision(storyRef, version, revised, List.of());
                currentPoHandoff = revised.handoff();
                storyMd = revised.storyMarkdown();
                q = agents.evaluateQuality(storyRef, "story", storyMd);
                board.saveQualityReport(storyRef, version, q);
            }
            qualityPassed = q.passed();

            // 4 + 5. Signal handlers (comment/approve/requestChanges/commentAdded) drive state
            // below; wait for gate 1: quality passed, two named distinct-identity approvals on the
            // current version, no open blocking comments.
            Workflow.await(this::gateSatisfied);
            board.transitionApproved(storyRef, version, 1);
            stage = CanonicalState.APPROVED;

            // 6. Plan: deterministic task breakdown from the spec delta's ADDED/MODIFIED scenarios.
            PlanHandoff plan = agents.planTasks(storyRef, currentPoHandoff);
            PublishTasksResult publishedTasks = board.publishTasks(storyRef, plan);
            String defaultBranch = publishedTasks.defaultBranch();
            stage = CanonicalState.PLANNED;

            // Advisory per-task quality pass: the plan agent is deterministic (no revise path), so
            // a failing task verdict is surfaced in the UI but never blocks the build loop.
            for (Task t : plan.tasks()) {
                String taskBoardId = publishedTasks.taskBoardIds().get(t.id());
                if (taskBoardId == null) {
                    continue;
                }
                WorkItemRef taskRef = new WorkItemRef(item.profile(), taskBoardId);
                QualityReport taskQuality = agents.evaluateQuality(taskRef, "task", taskContent(t));
                board.saveQualityReport(taskRef, 1, taskQuality);
            }

            // 7. Build loop: omp over ACP, one shared story branch, wave by wave (a wave only starts
            // once every earlier wave's tasks have returned). A build-task escalation (budget
            // exhausted / stuck / forbidden action) pauses for human input after every wave - see
            // resolveEscalations.
            String branch = "story/" + storyRef.boardId();
            board.transitionInProgress(storyRef);
            stage = CanonicalState.IN_PROGRESS;
            Map<String, Task> tasksById = new LinkedHashMap<>();
            for (Task t : plan.tasks()) {
                tasksById.put(t.id(), t);
            }
            List<BuildResult> results = new ArrayList<>();
            for (List<String> wave : plan.waves()) {
                List<BuildResult> waveResults = runWave(wave, tasksById, taskId -> List.of(), branch, defaultBranch);
                waveResults = resolveEscalations(waveResults, tasksById, branch, defaultBranch);
                results.addAll(waveResults);
            }

            board.recordTaskResults(storyRef, publishedTasks.taskBoardIds(), results);

            // 8. Review the accumulated diff once, open the PR, post findings; awaiting-G2.
            ReviewHandoff review = agents.reviewStory(storyRef, currentPoHandoff, plan.tasks(), results);
            board.openStoryPr(storyRef, branch, plan.tasks(), results, review);
            stage = CanonicalState.AWAITING_G2;

            // 9. Gate 2: reuse the same approve/comment/requestChanges signal surface, now scoped to
            // gate 2's roles (FSDeveloper, QA) and a fresh version/approval episode. Blocker findings
            // from the review agent seed the open-blocking-comments set so gate 2 cannot pass while
            // any remain open - the same mechanism gate 1 uses for human blocking comments
            // (ReviewHandoff#hasBlockers()'s contract, otherwise unenforced). A review-agent blocker
            // on a failing task sends that task back to the build loop once, automatically
            // (MAX_AUTO_FIX_ROUNDS); every human "Request changes" - blocker-backed or not - re-runs
            // the targeted tasks with the comments injected, re-reviews, and re-awaits, uncapped,
            // like gate 1.
            activeGate = gate2;
            version = 1;
            approvals.clear();
            comments.clear();
            seedBlockerComments(review);
            int autoFixRounds = 0;
            int fixRoundNumber = 0;
            while (true) {
                List<String> failing = failingTaskIds(results);
                if (!failing.isEmpty() && autoFixRounds < MAX_AUTO_FIX_ROUNDS) {
                    autoFixRounds++;
                    fixRoundNumber++;
                    version++;
                    approvals.clear();
                    comments.clear();
                    List<BuildResult> beforeRound = results;
                    results = fixRound(plan, tasksById, results, failing, branch, defaultBranch,
                            taskId -> verifierFeedback(resultOf(beforeRound, taskId)), publishedTasks, fixRoundNumber);
                    review = lastReview;
                    seedBlockerComments(review);
                    continue;
                }
                Workflow.await(() -> gateSatisfied() || pendingFixRequest != null);
                if (pendingFixRequest == null) {
                    break;
                }
                FixRequest req = pendingFixRequest;
                pendingFixRequest = null;
                List<Comment> feedbackComments = req.comments();
                if (feedbackComments.isEmpty()) {
                    feedbackComments = List.of(askWhatShouldChange(req.by()));
                }
                if (feedbackComments.size() == 1 && "skip".equalsIgnoreCase(feedbackComments.get(0).text())) {
                    // No fix round follows; re-await at the bumped version. board.postHumanInputRequest
                    // (inside askWhatShouldChange, when it ran) left the board at needs-clarification -
                    // flip it back so it doesn't stay stuck there indefinitely.
                    board.transitionAwaitingG2(storyRef);
                    stage = CanonicalState.AWAITING_G2;
                    continue;
                }
                List<Comment> roundFeedback = feedbackComments;
                List<String> targets = tasksTargetedBy(roundFeedback, plan.tasks());
                fixRoundNumber++;
                results = fixRound(plan, tasksById, results, targets, branch, defaultBranch,
                        taskId -> commentFeedback(roundFeedback, taskId, tasksById), publishedTasks, fixRoundNumber);
                review = lastReview;
                seedBlockerComments(review);
                autoFixRounds = 0; // a human round re-arms one auto round
            }
            board.transitionApproved(storyRef, version, 2);
            stage = CanonicalState.APPROVED;

            // 10. Release agent drafts the pack (playbook §7); publish it, awaiting-G3.
            ReleaseHandoff release = agents.draftReleasePack(storyRef, currentPoHandoff, plan.tasks(), results, review, List.of());
            board.publishReleasePack(storyRef, release);
            currentRelease = release;
            stage = CanonicalState.AWAITING_G3;

            // 11. Gate 3: a document-level "sign" is the same `approve` signal, `stage:
            // "release-pack:<doc-id>"`; gate 3 opens once every document has a signature from its
            // own named checker role (tech-stack §3.1, §3.4) - see gate3Satisfied/approveDocument.
            // A human "Request changes" re-drafts the pack with the comments injected and
            // republishes it at the bumped pack version, uncapped like gate 1/2.
            activeGate = gate3;
            version = 1;
            approvals.clear();
            documentSignatures.clear();
            comments.clear();
            while (true) {
                Workflow.await(() -> gateSatisfied() || pendingReleaseRedraft);
                if (!pendingReleaseRedraft) {
                    break;
                }
                pendingReleaseRedraft = false; // consume now: a signal during the redraft below re-arms it
                ReleaseHandoff redrafted = agents.draftReleasePack(storyRef, currentPoHandoff, plan.tasks(), results, review, releaseFeedback);
                // Keep the original releaseId stable across every redraft (the agent mints a fresh
                // one from today's date, which would otherwise orphan the release board item/folder
                // a redraft on a later day than the first publish would create a second one).
                release = new ReleaseHandoff(redrafted.envelope(), currentRelease.releaseId(),
                        redrafted.documents(), redrafted.rollout(), redrafted.monitorRules());
                board.publishReleaseRevision(storyRef, release, version);
                currentRelease = release;
            }
            board.transitionApproved(storyRef, version, 3);
            stage = CanonicalState.APPROVED;

            // 12. Deploy (playbook §7 step 6), then one monitor evaluation pass (playbook §8 step
            // 1-3). A true multi-day watch window (the real default) is out of pilot scope; this
            // proves the rule-evaluation -> evidence -> filed-card mechanism once, deterministically,
            // right after deploy - a later phase would re-trigger this on a schedule instead of
            // ending the workflow (or, for a multi-story feature, moving on to the next story).
            board.deployRelease(storyRef, release, branch);
            stage = CanonicalState.DONE;
            MonitorHandoff monitorResult = agents.evaluateMonitorRules(storyRef, release.monitorRules());
            board.fileMonitorCards(storyRef, monitorResult);
        }
    }

    /** Sets {@code needs-clarification} and blocks until every grill question is answered/parked,
     * escalating to {@code stale} at the 5-day timer (playbook §1 "Stops"); re-entrant so the PO
     * agent's follow-up rounds and the build loop's human-input questions can drive it again. */
    private void awaitClarification(WorkItemRef item) {
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
    }

    // -- build loop: waves, fix rounds, human-input escalations -----------------------------------

    /** Runs one wave of tasks concurrently via {@link BuildActivities#runTask}, each carrying its
     * own {@code feedback} lines (empty on a first attempt). Shared by the first build and every
     * fix round. */
    private List<BuildResult> runWave(List<String> taskIds, Map<String, Task> tasksById,
                                       Function<String, List<String>> feedback, String branch, String defaultBranch) {
        List<Promise<BuildResult>> pending = new ArrayList<>();
        for (String taskId : taskIds) {
            Task task = tasksById.get(taskId);
            // Every wave's tasks share one activity type ("runTask"), so the Temporal UI's
            // timeline/history view shows them as indistinguishable bars unless each execution
            // carries its own summary (SDK "fixed summary" - annotates that view specifically).
            BuildActivities taskBuild = Workflow.newActivityStub(BuildActivities.class,
                    ActivityOptions.newBuilder(BUILD_ACTIVITY_OPTIONS)
                            .setSummary(task.id() + ": " + task.scenario())
                            .build());
            pending.add(Async.function(taskBuild::runTask, storyRef, task, branch, defaultBranch, feedback.apply(taskId)));
        }
        List<BuildResult> waveResults = new ArrayList<>();
        for (Promise<BuildResult> p : pending) {
            waveResults.add(p.get());
        }
        return waveResults;
    }

    /** After every wave (first build and fix rounds): any result that escalated (budget exhausted /
     * stuck / forbidden action) and hasn't already asked {@link #MAX_HUMAN_INPUT_ROUNDS_PER_TASK}
     * times posts an {@code h*} question, pauses the story in {@code needs-clarification}, and
     * retries that one task with the human's guidance injected once answered; {@code skip}/park
     * keeps the escalated result as-is (it flows into review as a SHOULD finding, today's
     * behavior). A retry that escalates again asks again, up to the per-task cap. */
    private List<BuildResult> resolveEscalations(List<BuildResult> waveResults, Map<String, Task> tasksById,
                                                  String branch, String defaultBranch) {
        List<BuildResult> current = new ArrayList<>(waveResults);
        java.util.Set<String> decided = new java.util.LinkedHashSet<>();
        while (true) {
            List<BuildResult> pending = current.stream()
                    .filter(r -> r.escalation() != null
                            && !decided.contains(r.taskId())
                            && humanRoundsByTask.getOrDefault(r.taskId(), 0) < MAX_HUMAN_INPUT_ROUNDS_PER_TASK)
                    .toList();
            if (pending.isEmpty()) {
                return current;
            }

            Map<String, String> questionIdByTask = new LinkedHashMap<>();
            List<GrillQuestion> newQuestions = new ArrayList<>();
            for (BuildResult r : pending) {
                String id = GrillQuestion.HUMAN_INPUT_ID_PREFIX + (++humanQuestionCounter);
                questionIdByTask.put(r.taskId(), id);
                Task task = tasksById.get(r.taskId());
                newQuestions.add(new GrillQuestion(id, GrillQuestion.Category.BUILD,
                        "Task " + r.taskId() + " (" + task.scenario() + ") stopped: " + r.escalation()
                                + ". Reply with guidance to retry, or 'skip' to continue to review as-is.",
                        "trace: " + r.traceSummary(), GrillQuestion.Status.OPEN, null, null));
            }
            grill = grill.withFollowUps(newQuestions);
            board.postHumanInputRequest(storyRef, grill);
            awaitClarification(storyRef);
            board.transitionInProgress(storyRef);
            stage = CanonicalState.IN_PROGRESS;

            Map<String, BuildResult> byTaskId = new LinkedHashMap<>();
            for (BuildResult r : current) {
                byTaskId.put(r.taskId(), r);
            }
            for (BuildResult r : pending) {
                String qid = questionIdByTask.get(r.taskId());
                GrillQuestion answered = grill.questions().stream().filter(q -> q.id().equals(qid)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No answer recorded for " + qid));
                String text = answered.status() == GrillQuestion.Status.PARKED
                        ? "skip" : stripLeadingId(qid, answered.answer());
                if ("skip".equalsIgnoreCase(text)) {
                    decided.add(r.taskId()); // stop asking about this task; keep the escalated result as-is
                    continue;
                }
                humanRoundsByTask.merge(r.taskId(), 1, Integer::sum);
                String answeredBy = answered.answeredBy() == null ? "human" : answered.answeredBy();
                List<String> feedback = List.of("[escalation] " + r.escalation(), "[human/" + answeredBy + "] " + text);
                List<BuildResult> retried = runWave(List.of(r.taskId()), tasksById, taskId -> feedback, branch, defaultBranch);
                byTaskId.put(r.taskId(), retried.get(0));
            }
            current = new ArrayList<>(byTaskId.values());
        }
    }

    /** Re-runs the tasks in {@code targets} (wave by wave, skipping non-targets), merges their new
     * results into {@code results} by task id, records them, re-reviews the whole story, posts the
     * fix round, and returns the merged results. Sets {@link #lastReview}. Callers own the
     * version/approvals/comments bump around this call - uniform for both the automatic
     * (blocker-triggered) and human-requested paths. */
    private List<BuildResult> fixRound(PlanHandoff plan, Map<String, Task> tasksById, List<BuildResult> results,
                                        List<String> targets, String branch, String defaultBranch,
                                        Function<String, List<String>> feedback, PublishTasksResult publishedTasks, int round) {
        board.transitionInProgress(storyRef);
        stage = CanonicalState.IN_PROGRESS;
        List<BuildResult> rerunResults = new ArrayList<>();
        for (List<String> wave : plan.waves()) {
            List<String> waveTargets = wave.stream().filter(targets::contains).toList();
            if (waveTargets.isEmpty()) {
                continue;
            }
            List<BuildResult> waveResults = runWave(waveTargets, tasksById, feedback, branch, defaultBranch);
            waveResults = resolveEscalations(waveResults, tasksById, branch, defaultBranch);
            rerunResults.addAll(waveResults);
        }

        Map<String, BuildResult> merged = new LinkedHashMap<>();
        for (BuildResult r : results) {
            merged.put(r.taskId(), r);
        }
        for (BuildResult r : rerunResults) {
            merged.put(r.taskId(), r);
        }
        List<BuildResult> mergedResults = List.copyOf(merged.values());

        board.recordTaskResults(storyRef, publishedTasks.taskBoardIds(), rerunResults);
        lastReview = agents.reviewStory(storyRef, currentPoHandoff, plan.tasks(), mergedResults);
        board.postFixRound(storyRef, branch, plan.tasks(), rerunResults, lastReview, round);
        stage = CanonicalState.AWAITING_G2;
        return mergedResults;
    }

    /** A human "Request changes" at gate 2 with zero comments: asks what should change via an
     * {@code h*} question rather than blindly re-running every task. A real answer becomes a
     * general {@code pr}-targeted comment (maps to no specific task, so {@link #tasksTargetedBy}
     * re-runs every task); a parked/{@code skip} answer becomes a comment whose text is literally
     * {@code "skip"}, which the gate-2 loop treats as "no fix round". */
    private Comment askWhatShouldChange(String requestedBy) {
        String id = GrillQuestion.HUMAN_INPUT_ID_PREFIX + (++humanQuestionCounter);
        GrillQuestion question = new GrillQuestion(id, GrillQuestion.Category.BUILD,
                "Changes were requested on the PR by " + requestedBy + " with no comments. What should change?",
                GrillQuestion.ASSUMPTION_CHECK, GrillQuestion.Status.OPEN, null, null);
        grill = grill.withFollowUps(List.of(question));
        board.postHumanInputRequest(storyRef, grill);
        awaitClarification(storyRef);

        GrillQuestion answered = grill.questions().stream().filter(q -> q.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No answer recorded for " + id));
        String text = answered.status() == GrillQuestion.Status.PARKED ? "skip" : stripLeadingId(id, answered.answer());
        String answeredBy = answered.answeredBy() == null ? requestedBy : answered.answeredBy();
        return new Comment(id, answeredBy, "human", "pr", "pr", text, Comment.Intent.CHANGE, false, version);
    }

    /** Tasks eligible for an automatic fix round: a fresh verifier failure the review agent
     * hasn't seen yet. Excludes an already-escalated result - a human already decided its fate via
     * {@link #resolveEscalations} (guidance-and-retry, or an explicit skip), and an automatic round
     * re-running it immediately would override that decision. */
    private static List<String> failingTaskIds(List<BuildResult> results) {
        return results.stream()
                .filter(r -> r.escalation() == null && (!r.verifier().scopeOk() || !"green".equals(r.verifier().result())))
                .map(BuildResult::taskId)
                .toList();
    }

    private static List<String> verifierFeedback(BuildResult r) {
        List<String> lines = new ArrayList<>();
        lines.add("[verifier] " + r.verifier().notes());
        if (r.escalation() != null) {
            lines.add("[escalation] " + r.escalation());
        }
        return lines;
    }

    /** Every comment that targets this task (per {@link #commentTargetsTask}) or targets no task
     * at all (a general comment applies to every rerun task). */
    private static List<String> commentFeedback(List<Comment> comments, String taskId, Map<String, Task> tasksById) {
        Task task = tasksById.get(taskId);
        List<String> lines = new ArrayList<>();
        for (Comment c : comments) {
            if (commentTargetsTask(c, task)) {
                lines.add("[" + c.role() + "] " + c.target() + ": " + c.text());
            }
        }
        return lines;
    }

    private static boolean commentTargetsTask(Comment c, Task task) {
        String target = c.target();
        if (target == null) {
            return true;
        }
        if (target.startsWith("task:")) {
            return target.substring("task:".length()).equals(task.id());
        }
        if (target.startsWith("file:")) {
            String path = filePathFromTarget(target);
            return task.touches().contains(path) || path.equals(task.testPath());
        }
        return true; // any other target (e.g. "pr", "doc:...") is general
    }

    /** Target {@code task:<id>} -> that task; {@code file:<path>} or {@code file:<path>:<line>} ->
     * every task whose {@code touches} or {@code testPath} equals {@code <path>}; any other target
     * maps to no task. The union of every comment's targets; empty union re-runs every task (a
     * general comment re-runs everything). */
    private static List<String> tasksTargetedBy(List<Comment> comments, List<Task> tasks) {
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
        for (Comment c : comments) {
            String target = c.target();
            if (target == null) {
                continue;
            }
            if (target.startsWith("task:")) {
                targets.add(target.substring("task:".length()));
            } else if (target.startsWith("file:")) {
                String path = filePathFromTarget(target);
                for (Task t : tasks) {
                    if (t.touches().contains(path) || path.equals(t.testPath())) {
                        targets.add(t.id());
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            return tasks.stream().map(Task::id).toList();
        }
        return List.copyOf(targets);
    }

    private static String filePathFromTarget(String target) {
        String rest = target.substring("file:".length());
        int lastColon = rest.lastIndexOf(':');
        if (lastColon > 0 && lastColon < rest.length() - 1 && isDigits(rest.substring(lastColon + 1))) {
            return rest.substring(0, lastColon);
        }
        return rest;
    }

    private static boolean isDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static BuildResult resultOf(List<BuildResult> results, String taskId) {
        return results.stream().filter(r -> r.taskId().equals(taskId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No result for task " + taskId));
    }

    /** Strips a leading {@code "<id>: "} marker from a grill answer, case-insensitively - the real
     * {@code GrillAgent} already stores the answer body alone, but tolerating the marker here keeps
     * this workflow's own parsing independent of that agent's exact behavior. */
    private static String stripLeadingId(String id, String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        String prefix = id + ":";
        if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return trimmed.substring(prefix.length()).strip();
        }
        return trimmed;
    }

    /** Seeds one blocking comment per review-agent BLOCKER finding, so gate 2 cannot pass while any
     * remain open - the same mechanism gate 1 uses for human blocking comments (ReviewHandoff
     * #hasBlockers()'s contract, otherwise unenforced). */
    private void seedBlockerComments(ReviewHandoff review) {
        int findingIndex = 0;
        for (ReviewFinding finding : review.findings()) {
            if (finding.severity() == ReviewFinding.Severity.BLOCKER) {
                comments.add(new Comment("finding-" + findingIndex++, REVIEW_BOT_IDENTITY, "review-agent", "pr",
                        finding.file() != null ? finding.file() : finding.category(), finding.message(),
                        Comment.Intent.CHANGE, true, version));
            }
        }
    }

    // -- gate signal surface ------------------------------------------------------------------

    private boolean gateSatisfied() {
        if (stage == CanonicalState.AWAITING_G1 && !qualityPassed) {
            return false;
        }
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
            QualityReport q = agents.evaluateQuality(storyRef, "story", revised.storyMarkdown());
            board.saveQualityReport(storyRef, version, q);
            qualityPassed = q.passed();
        }
        if (stage == CanonicalState.AWAITING_G2) {
            pendingFixRequest = new FixRequest(by, List.copyOf(comments));
        }
        if (stage == CanonicalState.AWAITING_G3) {
            documentSignatures.clear(); // pack_version bump invalidates every signature (playbook §7 "Rules")
            releaseFeedback = List.copyOf(comments);
            pendingReleaseRedraft = true;
        }
        // G2/G3 re-runs happen in run()'s gate loops, driven by pendingFixRequest / pendingReleaseRedraft.
        comments.clear(); // every open comment is considered handled by this revision/version bump
    }

    @Override
    public void commentAdded(BoardCommentEvent e) {
        pendingBoardComments.add(e);
    }

    @Override
    public ReviewState state() {
        Map<String, Approval> currentApprovals = stage == CanonicalState.AWAITING_G3 ? documentSignatures : approvals;
        return new ReviewState(version, Map.copyOf(currentApprovals), openBlockingComments(), stage,
                storyRef == null ? null : storyRef.boardId());
    }

    @Override
    public GrillHandoff grill() {
        return grill;
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

    /** Advisory task-quality content: a task has no markdown draft of its own (unlike a story), so
     * this assembles the same fields {@code BoardSideEffectsImpl#taskDescription} writes to the
     * board item description, for the quality agent to evaluate. */
    private static String taskContent(Task t) {
        return "# " + t.title() + "\n\nScenario: " + t.scenario() + "\nArea: " + t.area()
                + "\nTouches: " + String.join(", ", t.touches()) + "\nTest: " + t.testPath();
    }
}
