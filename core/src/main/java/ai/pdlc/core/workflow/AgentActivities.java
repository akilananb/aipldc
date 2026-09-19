package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.AgentMentionRequest;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillRound;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.MonitorRule;
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
     * six-category questions. Re-run: marks each question {@code answered} (referenced with
     * {@code <id>: text}) or {@code parked} (referenced with {@code <id>: park}) from the human
     * comments in {@code newComments}; a question not referenced by id stays {@code open}; never
     * answers its own questions.
     */
    @ActivityMethod
    GrillHandoff grillEvaluate(WorkItemRef item, GrillHandoff previous, List<BoardCommentEvent> newComments);

    /** One adaptive-intake reasoning round (ADAPTIVE_GRILL_PLAN.md step 3). {@code previous ==
     * null} starts intake; otherwise every question in {@code previous} must already be resolved
     * (answered/parked) — the caller folds human answers via {@link #grillEvaluate} first. Returns
     * only the newly-appended OPEN questions (or none, with a nonblank completion summary) — never
     * re-generates or overwrites prior history. */
    @ActivityMethod
    GrillRound grillNextRound(WorkItemRef item, GrillHandoff previous);

    /** Drafts the story + spec delta from the resolved grill handoff. Returns one {@link StoryDraft}
     * per distinct actor/factor when the PO agent splits the feature into multiple stories
     * (single-actor features return a singleton list). When {@code allowFollowUps}, the agent may
     * instead return follow-up questions ({@link PoDraftResult#needsClarification()}) it needs
     * answered before it can draft; when {@code false} it must draft. */
    @ActivityMethod
    PoDraftResult poDraft(WorkItemRef item, GrillHandoff grill, boolean allowFollowUps);

    /** Revises the current story to address the submitted feedback (every explicitly-posted
     * comment, not just blocking ones), retaining the resolved intake context ({@code grill},
     * possibly {@code null} for an already-queued three-argument activity input) so an
     * unrelated-content revision cannot regress DoR/parked-scope coverage; re-runs INVEST/DoR. */
    @ActivityMethod
    StoryDraft poRevise(WorkItemRef item, PoHandoff previous, List<Comment> openComments, GrillHandoff grill);

    /** Evaluates a story draft's INVEST/clarity/testability quality (playbook: quality agent).
     * {@code subjectKind} is always {@code "story"} (task plan checks are deterministic — see
     * {@link ai.pdlc.core.plan.PlanChecks} — not LLM-evaluated); the verdict hard-blocks gate 1. */
    @ActivityMethod
    QualityReport evaluateQuality(WorkItemRef item, String subjectKind, String contentMd);

    /** Reviews the accumulated story branch diff once the build loop finishes every wave:
     * traceability (scenario → test → code) plus findings: blocker/should/nit. */
    @ActivityMethod
    ReviewHandoff reviewStory(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results);

    /** Drafts the release pack (playbook §7): change notes, rollout/rollback plan, monitor rules,
     * test evidence — one document per checker role, from the story, build results, and review.
     * {@code feedback}: the gate-3 comments a re-draft must address; empty on the first draft. */
    @ActivityMethod
    ReleaseHandoff draftReleasePack(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results, ReviewHandoff review, List<Comment> feedback);

    /** Evaluates each monitor rule against {@link ai.pdlc.core.port.MetricsPort}, comparing to a
     * baseline; a trip gathers evidence for one filed card (playbook §8). */
    @ActivityMethod
    MonitorHandoff evaluateMonitorRules(WorkItemRef story, List<MonitorRule> rules);

    /** One-shot LLM analysis for an @-mentioned agent (analyst|architect|qa); returns raw markdown. */
    @ActivityMethod
    String mentionAnalyze(AgentMentionRequest request);
}
