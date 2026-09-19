package ai.pdlc.agents.activities;

import ai.pdlc.agents.grill.GrillAgent;
import ai.pdlc.agents.mention.MentionAgent;
import ai.pdlc.agents.monitor.MonitorAgent;
import ai.pdlc.agents.po.PoAgent;
import ai.pdlc.agents.quality.QualityAgent;
import ai.pdlc.agents.release.ReleaseAgent;
import ai.pdlc.agents.review.ReviewAgent;
import ai.pdlc.agents.tracing.AgentRunTracer;
import ai.pdlc.core.domain.AgentMentionRequest;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillRound;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.MetricsPort;
import ai.pdlc.core.workflow.AgentActivities;
import ai.pdlc.core.workflow.BoardCommentEvent;
import ai.pdlc.core.workflow.BuildResult;
import ai.pdlc.core.workflow.PoDraftResult;
import ai.pdlc.core.workflow.StoryDraft;
import io.temporal.activity.Activity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Hosts {@link AgentActivities} on the {@code reasoning} queue. Pure reasoning: gathers context
 * best-effort, invokes the grill/PO agents, and records one {@code runs} row per invocation,
 * wrapped in one Langfuse trace ({@link AgentRunTracer}) (plan step 6). No board/DB writes other
 * than the {@code runs} insert — board/repo side effects stay in control-plane's {@code
 * BoardSideEffects}.
 */
@Component
public class AgentActivitiesImpl implements AgentActivities {

    private final GrillAgent grillAgent;
    private final PoAgent poAgent;
    private final ReviewAgent reviewAgent;
    private final ReleaseAgent releaseAgent;
    private final MonitorAgent monitorAgent;
    private final MentionAgent mentionAgent;
    private final QualityAgent qualityAgent;
    private final MetricsPort metrics;
    private final RunRecorder runs;
    private final AgentRunTracer tracer;

    public AgentActivitiesImpl(GrillAgent grillAgent, PoAgent poAgent,
                                ReviewAgent reviewAgent, ReleaseAgent releaseAgent, MonitorAgent monitorAgent,
                                MentionAgent mentionAgent, QualityAgent qualityAgent, MetricsPort metrics, RunRecorder runs,
                                AgentRunTracer tracer) {
        this.grillAgent = grillAgent;
        this.poAgent = poAgent;
        this.reviewAgent = reviewAgent;
        this.releaseAgent = releaseAgent;
        this.monitorAgent = monitorAgent;
        this.mentionAgent = mentionAgent;
        this.qualityAgent = qualityAgent;
        this.metrics = metrics;
        this.runs = runs;
        this.tracer = tracer;
    }

    @Override
    public GrillHandoff grillEvaluate(WorkItemRef item, GrillHandoff previous, List<BoardCommentEvent> newComments) {
        String phase = previous == null ? "questions" : "answers";
        return traced("grill", phase, item, () -> grillAgent.evaluate(item, previous, newComments));
    }

    @Override
    public GrillRound grillNextRound(WorkItemRef item, GrillHandoff previous) {
        return traced("grill", "round", item, () -> grillAgent.nextRound(item, previous));
    }

    @Override
    public PoDraftResult poDraft(WorkItemRef item, GrillHandoff grill, boolean allowFollowUps) {
        return traced("po", "draft", item, () -> poAgent.draft(item, grill, allowFollowUps));
    }

    @Override
    public StoryDraft poRevise(WorkItemRef item, PoHandoff previous, List<Comment> openComments, GrillHandoff grill) {
        return traced("po", "revise", item, () -> poAgent.revise(item, previous, openComments, grill));
    }

    @Override
    public QualityReport evaluateQuality(WorkItemRef item, String subjectKind, String contentMd) {
        return traced("quality", subjectKind, item, () -> qualityAgent.evaluate(subjectKind, contentMd));
    }

    @Override
    public ReviewHandoff reviewStory(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results) {
        return traced("review", "review", story, () -> reviewAgent.review(story, po, tasks, results));
    }

    @Override
    public ReleaseHandoff draftReleasePack(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results, ReviewHandoff review, List<Comment> feedback) {
        String phase = feedback.isEmpty() ? "draft" : "redraft";
        return traced("release", phase, story, () -> releaseAgent.draft(story, po, tasks, results, review, feedback));
    }

    @Override
    public MonitorHandoff evaluateMonitorRules(WorkItemRef story, List<MonitorRule> rules) {
        return traced("monitor", "evaluate", story, () -> monitorAgent.evaluate(story, rules, metrics));
    }

    @Override
    public String mentionAnalyze(AgentMentionRequest request) {
        UUID workItemId = UUID.fromString(request.workItemId());
        String workflowId = workflowId();
        return tracer.trace("mention", null, workItemId, workflowId, run -> {
            UUID runId = runs.startById(workItemId, "mention", request.agentName(), workflowId, run.traceUrl());
            try {
                String result = mentionAgent.analyze(request);
                runs.finish(runId, "ok");
                return result;
            } catch (RuntimeException e) {
                runs.finish(runId, "error");
                throw e;
            }
        });
    }

    /** Wraps one agent invocation in a Langfuse trace and records the {@code runs} row with the
     * resulting trace URL. */
    private <T> T traced(String agent, String phase, WorkItemRef item, Supplier<T> body) {
        String workflowId = workflowId();
        return tracer.trace(agent, item, null, workflowId, run -> {
            UUID runId = runs.start(item, agent, phase, workflowId, run.traceUrl());
            try {
                T result = body.get();
                runs.finish(runId, "ok");
                return result;
            } catch (RuntimeException e) {
                runs.finish(runId, "error");
                throw e;
            }
        });
    }

    private static String workflowId() {
        try {
            return Activity.getExecutionContext().getInfo().getWorkflowId();
        } catch (RuntimeException e) {
            return null; // not inside an activity (e.g. a direct unit test)
        }
    }
}
