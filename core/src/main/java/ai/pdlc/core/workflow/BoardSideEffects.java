package ai.pdlc.core.workflow;

import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

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

    /** Posts the grill questions as a single comment on the work item, one numbered question per
     * line, category prefixed (playbook §1 "Does" step 3). */
    @ActivityMethod
    void postGrillQuestions(WorkItemRef item, GrillHandoff grill);

    /** All questions answered/parked: {@code board.transition(ready-for-story)} + attach {@code grill.md}. */
    @ActivityMethod
    void transitionReadyForStory(WorkItemRef item, GrillHandoff grill);

    /** Creates the child User Story, writes the spec delta + story files, inserts {@code artifacts} v1,
     * appends the {@code review.md} v1 block, sets {@code awaiting-G1}. */
    @ActivityMethod
    PublishResult publishStory(WorkItemRef feature, StoryDraft draft);

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
     * profile's repo default branch — the target the story's PR opens against. */
    @ActivityMethod
    String publishTasks(WorkItemRef story, PlanHandoff plan);

    /** Build loop starting: {@code board.transition(in-progress)}. */
    @ActivityMethod
    void transitionInProgress(WorkItemRef story);

    /** Every wave green (or escalated per playbook §4 "budget exhausted → WIP branch + escalation
     * note, correct outcome"): opens the PR for {@code branch}, records one {@code runs} row per
     * task, posts the review agent's findings as PR comments, appends the review.md "PR opened"
     * block, sets {@code awaiting-G2}. */
    @ActivityMethod
    PRRef openStoryPr(WorkItemRef story, String branch, List<Task> tasks, List<BuildResult> results, ReviewHandoff review);

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
}
