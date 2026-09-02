package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.AgentMentionRequest;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * Reasoning activities — grill and PO agents, hosted by the {@code agents} module's Embabel-backed
 * implementation on task queue {@link TaskQueues#REASONING}. Pure reasoning: reads board/repo context
 * for the item but performs no board/DB writes (those are {@link BoardSideEffects}, so writes always
 * go through control-plane's adapters and datasource).
 */
@ActivityInterface
public interface AgentActivities {

    /**
     * First call: {@code previous} is {@code null} and {@code newComments} is empty — generates the
     * six-category questions. Re-run: marks each question {@code answered}/{@code parked}/{@code open}
     * from the human comments in {@code newComments}; never answers its own questions.
     */
    @ActivityMethod
    GrillHandoff grillEvaluate(WorkItemRef item, GrillHandoff previous, List<BoardCommentEvent> newComments);

    /** Drafts the story + spec delta from the resolved grill handoff. Returns one {@link StoryDraft}
     * per distinct actor/factor when the PO agent splits the feature into multiple stories
     * (single-actor features return a singleton list). */
    @ActivityMethod
    List<StoryDraft> poDraft(WorkItemRef item, GrillHandoff grill);

    /** Revises only the lines the open comments target; re-runs INVEST/DoR; replies to every comment. */
    @ActivityMethod
    StoryDraft poRevise(WorkItemRef item, PoHandoff previous, List<Comment> openComments);

    /** Evaluates one story/task draft's INVEST/clarity/testability quality (playbook: quality
     * agent). {@code subjectKind} is {@code "story"} or {@code "task"}; a story verdict hard-blocks
     * gate 1, a task verdict is advisory only. */
    @ActivityMethod
    QualityReport evaluateQuality(WorkItemRef item, String subjectKind, String contentMd);

    /** Deterministic task breakdown from the approved story's spec delta — one task per
     * ADDED/MODIFIED scenario, sequenced into waves by file-conflict/dependency order. */
    @ActivityMethod
    PlanHandoff planTasks(WorkItemRef story, PoHandoff po);

    /** Reviews the accumulated story branch diff once the build loop finishes every wave:
     * traceability (scenario → test → code) plus findings: blocker/should/nit. */
    @ActivityMethod
    ReviewHandoff reviewStory(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results);

    /** Drafts the release pack (playbook §7): change notes, rollout/rollback plan, monitor rules,
     * test evidence — one document per checker role, from the story, build results, and review. */
    @ActivityMethod
    ReleaseHandoff draftReleasePack(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results, ReviewHandoff review);

    /** Evaluates each monitor rule against {@link ai.pdlc.core.port.MetricsPort}, comparing to a
     * baseline; a trip gathers evidence for one filed card (playbook §8). */
    @ActivityMethod
    MonitorHandoff evaluateMonitorRules(WorkItemRef story, List<MonitorRule> rules);

    /** One-shot LLM analysis for an @-mentioned agent (analyst|architect|qa); returns raw markdown. */
    @ActivityMethod
    String mentionAnalyze(AgentMentionRequest request);
}
