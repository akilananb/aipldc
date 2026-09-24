package ai.pdlc.core.workflow;

import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;

/**
 * Board/repo/DB write activities, hosted by control-plane's own worker on task queue
 * {@link TaskQueues#REASONING} so every write goes through control-plane's adapters and datasource
 * (orchestration-decision §6). The workflow calls these; it never touches {@code BoardPort}/{@code
 * RepoPort} itself (determinism rule, orchestration-decision §5).
 */
@ActivityInterface
public interface BoardSideEffects {

    /** Reads {@code gates.G1} for {@code profile} from {@code pdlc.yaml}; fails workflow start if absent. */
    @ActivityMethod
    GateConfig loadGate1Config(String profile);

    /** The project's repos at the moment planning starts — {@code ai.pdlc.core.workflow.PlanningLoop}
     * and {@link ai.pdlc.core.plan.PlanAssembler} require every task/CONSULT round to name one. */
    @ActivityMethod
    List<RepoConfig> loadRepos(String profile);

    /** Posts the grill questions as a single comment on the work item, one numbered question per
     * line, category prefixed (playbook §1 "Does" step 3). */
    @ActivityMethod
    void postGrillQuestions(WorkItemRef item, GrillHandoff grill);

    /** All questions answered/parked: {@code board.transition(ready-for-story)} + attach {@code grill.md}. */
    @ActivityMethod
    void transitionReadyForStory(WorkItemRef item, GrillHandoff grill);

    /** PO agent asked follow-ups: posts the open {@code po*} questions as one comment by the PO bot,
     * sets {@code needs-clarification}, re-attaches {@code grill.md}, appends
     * {@code review_events: po-follow-up}. */
    @ActivityMethod
    void postFollowUpQuestions(WorkItemRef item, GrillHandoff grill);

    /** Publishes one adaptive-intake round (ADAPTIVE_GRILL_PLAN.md step 4): posts the currently
     * OPEN grill {@code q*} questions (not historical answers, not po/h questions) as one comment
     * by the Grill bot, sets {@code needs-clarification}, and re-attaches the full updated
     * {@code grill.md} — including the final confirmation round. */
    @ActivityMethod
    void postGrillRound(WorkItemRef item, GrillHandoff grill);

    /** Creates the child User Story, writes the spec delta + story files, inserts {@code artifacts} v1,
     * appends the {@code review.md} v1 block. {@code storyIndex} is this story's position among a
     * multi-story split (0 = first/active); {@code queued} sets state {@code queued} instead of
     * {@code awaiting-G1} for every story after the first — it only becomes {@code awaiting-G1} once
     * {@link #activateStory} runs. */
    @ActivityMethod
    PublishResult publishStory(WorkItemRef feature, StoryDraft draft, int storyIndex, boolean queued);

    /** Writes the revised story/spec delta, inserts the new {@code artifacts} row, appends the
     * {@code review.md} revision block, replies to the given comment ids as resolved. */
    @ActivityMethod
    PublishResult publishRevision(WorkItemRef story, int newVersion, StoryDraft draft, List<String> resolvedCommentIds);

    /** Gate satisfied: {@code board.transition(approved)}, append "gate N passed" line + {@code
     * review_events: gate-passed}. */
    @ActivityMethod
    void transitionApproved(WorkItemRef story, int version, int gate);

    /** 5 working days with open grill questions: tag Squad Lead, {@code board.transition(stale)},
     * append {@code review_events: stale-escalation}. */
    @ActivityMethod
    void escalateStale(WorkItemRef item);

    /** Reads {@code gates.G2} for {@code profile} from {@code pdlc.yaml}; fails workflow start if absent. */
    @ActivityMethod
    GateConfig loadGate2Config(String profile);

    /** Creates one board Task item per {@link Task} (kind {@code task}, parent = the story),
     * writes {@code tasks.md} next to the story's spec delta, sets {@code planned}. Returns the
     * profile's repo default branch (the target the story's PR opens against) plus each task's
     * board id, keyed by {@link Task#id()} — used to file each task's advisory quality report. */
    @ActivityMethod
    PublishTasksResult publishTasks(WorkItemRef story, PlanHandoff plan);

    /** Reads {@code gates.PLAN} for {@code profile} from {@code pdlc.yaml}; fails workflow start
     * if absent. */
    @ActivityMethod
    GateConfig loadPlanGateConfig(String profile);

    /** A SquadLead sent the task plan back for changes: re-plans and republishes {@code tasks.md}
     * without leaving {@code planned}. Reuses each still-present task's {@code _idempotencyKey}
     * (updating its title/description); any task id from {@code previousTaskBoardIds} that is
     * absent from the new plan is marked "[superseded]" (title prefix + comment) rather than
     * removed - {@link ai.pdlc.core.domain.CanonicalState} has no cancelled value. */
    @ActivityMethod
    PublishTasksResult republishTasks(WorkItemRef story, PlanHandoff plan, Map<String, String> previousTaskBoardIds, int planVersion);

    /** The plan gate passed: appends {@code review_events: plan-approved} + a {@code review.md}
     * line. No board transition here - the caller ({@link FeatureWorkflowImpl#run}) transitions
     * to {@code in-progress} once the build loop actually starts. */
    @ActivityMethod
    void recordPlanApproved(WorkItemRef story, int planVersion);

    /** Build loop starting: {@code board.transition(in-progress)}. */
    @ActivityMethod
    void transitionInProgress(WorkItemRef story);

    /** Every wave green (or escalated per playbook §4 "budget exhausted → WIP branch + escalation
     * note, correct outcome"): opens the PR for {@code branch}, records one {@code runs} row per
     * task, posts the review agent's findings as PR comments, appends the review.md "PR opened"
     * block, sets {@code awaiting-G2}. */
    @ActivityMethod
    List<PRRef> openStoryPr(WorkItemRef story, String branch, List<Task> tasks, List<BuildResult> results, ReviewHandoff review);

    /** Build loop finished: transitions each task's board card to {@code done} and re-saves its
     * quality report from the real verifier outcome (green/red), superseding the pre-build
     * advisory-only check {@link #publishTasks} recorded (task board cards otherwise stay stuck
     * at {@code new} with a stale quality badge forever, even once the story reaches {@code done}).
     * No-op for any task whose board id is unknown, mirroring {@link #publishTasks}'s own guard. */
    @ActivityMethod
    void recordTaskResults(WorkItemRef story, Map<String, String> taskBoardIds, List<BuildResult> results);

    /** Reads {@code gates.G3} for {@code profile} from {@code pdlc.yaml}; fails workflow start if absent. */
    @ActivityMethod
    GateConfig loadGate3Config(String profile);

    /** Creates the release work item (kind {@code release}, parent = the story), writes each
     * release document plus {@code manifest.yaml} under {@code release/<releaseId>/}, sets
     * {@code awaiting-G3} (playbook §7 "Produces"). */
    @ActivityMethod
    void publishReleasePack(WorkItemRef story, ReleaseHandoff release);

    /** Gate 3 passed: calls {@link ai.pdlc.core.port.CiPort#runDeploy} for {@code branch}, records
     * the outcome, sets {@code done} on success (playbook §7 step 6 "triggers the pipeline deploy
     * stage"). Throws if the deploy run fails - the pilot does not implement canary/promote. */
    @ActivityMethod
    void deployRelease(WorkItemRef story, ReleaseHandoff release, String branch);

    /** One card per tripped rule (type {@code bug|feature}, linked to the story and release,
     * evidence attached, state {@code new} — playbook §8 "Produces"). No-op if nothing tripped. */
    @ActivityMethod
    void fileMonitorCards(WorkItemRef story, MonitorHandoff monitor);

    /** Stores the agent's draft (status pending|failed) on the mention comment; appends review_events. */
    @ActivityMethod
    void saveAgentMentionResult(String commentId, String markdown, String status);

    /** Durably records a step's bounded-retry-exhausted failure: {@code review_events: step-failed}
     * always, plus a {@code review.md} block when {@code ref}'s work item has a spec change path
     * (mirrors {@link #saveQualityReport}'s dual-trail-when-available shape). Called from within
     * {@link FeatureWorkflowImpl}'s {@code reasoningStep} retry loop — the workflow blocks in
     * place afterward, awaiting {@link FeatureWorkflow#retryStep}, rather than dying. */
    @ActivityMethod
    void recordStepFailure(WorkItemRef ref, String step, String message);

    /** A queued story (see {@link #publishStory}) becomes the pipeline's active story once the
     * previous story's episode finishes: {@code board.transition(awaiting-G1)}. */
    @ActivityMethod
    void activateStory(WorkItemRef story);

    /** Persists one quality-agent verdict for a story/task draft version and appends it to the
     * dual audit trail (review_events always; review.md too, for stories). */
    @ActivityMethod
    void saveQualityReport(WorkItemRef item, int version, QualityReport report);

    /** A build task stopped and needs a human decision: posts the open {@code h*} questions as one
     * comment on the story (build bot), sets the story {@code needs-clarification}, appends
     * {@code review_events: needs-human}. */
    @ActivityMethod
    void postHumanInputRequest(WorkItemRef story, GrillHandoff grill);

    /** After a fix round: comments the new findings on the story's existing PR, records one runs
     * row per re-run task, appends the review.md fix-round block + {@code review_events: fix-round},
     * sets {@code awaiting-G2}. */
    @ActivityMethod
    void postFixRound(WorkItemRef story, String branch, List<Task> tasks, List<BuildResult> rerunResults, ReviewHandoff review, int round);

    /** Gate 3 request-changes: rewrites the release documents under {@code release/<releaseId>/},
     * inserts one {@code release_documents} row per document at {@code packVersion}, appends
     * review.md + {@code review_events: release-pack-revised}. No new board item, state stays
     * {@code awaiting-G3}. */
    @ActivityMethod
    void publishReleaseRevision(WorkItemRef story, ReleaseHandoff release, int packVersion);

    /** A human answered "what should change" with skip/park (no fix round follows): flips the
     * story back from {@code needs-clarification} to {@code awaiting-G2} - the board-side mirror
     * of what {@link #postFixRound}/{@link #openStoryPr} already do at the end of every other path
     * through gate 2. */
    @ActivityMethod
    void transitionAwaitingG2(WorkItemRef story);
}
