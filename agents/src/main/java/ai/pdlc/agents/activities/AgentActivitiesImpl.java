package ai.pdlc.agents.activities;

import ai.pdlc.agents.grill.GrillAgent;
import ai.pdlc.agents.mention.MentionAgent;
import ai.pdlc.agents.monitor.MonitorAgent;
import ai.pdlc.agents.plan.PlanAgent;
import ai.pdlc.agents.po.PoAgent;
import ai.pdlc.agents.quality.QualityAgent;
import ai.pdlc.agents.release.ReleaseAgent;
import ai.pdlc.agents.review.ReviewAgent;
import ai.pdlc.agents.tracing.AgentRunTracer;
import ai.pdlc.core.domain.AgentMentionRequest;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.PlanHandoff;
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
    private final PlanAgent planAgent;
    private final ReviewAgent reviewAgent;
    private final ReleaseAgent releaseAgent;
    private final MonitorAgent monitorAgent;
    private final MentionAgent mentionAgent;
    private final QualityAgent qualityAgent;
    private final MetricsPort metrics;
    private final RunRecorder runs;
    private final AgentRunTracer tracer;

    public AgentActivitiesImpl(GrillAgent grillAgent, PoAgent poAgent, PlanAgent planAgent,
                                ReviewAgent reviewAgent, ReleaseAgent releaseAgent, MonitorAgent monitorAgent,
                                MentionAgent mentionAgent, QualityAgent qualityAgent, MetricsPort metrics, RunRecorder runs,
                                AgentRunTracer tracer) {
        this.grillAgent = grillAgent;
        this.poAgent = poAgent;
        this.planAgent = planAgent;
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
        return traced("grill", item, () -> grillAgent.evaluate(item, previous, newComments));
    }

    @Override
    public PoDraftResult poDraft(WorkItemRef item, GrillHandoff grill, boolean allowFollowUps) {
        return traced("po", item, () -> poAgent.draft(item, grill, allowFollowUps));
    }

    @Override
    public StoryDraft poRevise(WorkItemRef item, PoHandoff previous, List<Comment> openComments) {
        return traced("po", item, () -> poAgent.revise(item, previous, openComments));
    }

    @Override
    public QualityReport evaluateQuality(WorkItemRef item, String subjectKind, String contentMd) {
        return traced("quality", item, () -> qualityAgent.evaluate(subjectKind, contentMd));
    }

    @Override
    public PlanHandoff planTasks(WorkItemRef story, PoHandoff po) {
        return traced("plan", story, () -> planAgent.plan(story, po));
    }

    @Override
    public ReviewHandoff reviewStory(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results) {
        return traced("review", story, () -> reviewAgent.review(story, po, tasks, results));
    }

    @Override
    public ReleaseHandoff draftReleasePack(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results, ReviewHandoff review) {
        return traced("release", story, () -> releaseAgent.draft(story, po, tasks, results, review));
    }

    @Override
    public MonitorHandoff evaluateMonitorRules(WorkItemRef story, List<MonitorRule> rules) {
        return traced("monitor", story, () -> monitorAgent.evaluate(story, rules, metrics));
    }

    @Override
    public String mentionAnalyze(AgentMentionRequest request) {
        UUID workItemId = UUID.fromString(request.workItemId());
        String workflowId = workflowId();
        return tracer.trace("mention", null, workItemId, workflowId, run -> {
            try {
                String result = mentionAgent.analyze(request);
                runs.recordById(workItemId, "mention", workflowId, run.traceUrl(), "ok", null, null);
                return result;
            } catch (RuntimeException e) {
                runs.recordById(workItemId, "mention", workflowId, run.traceUrl(), "error", null, null);
                throw e;
            }
        });
    }

    /** Wraps one agent invocation in a Langfuse trace and records the {@code runs} row with the
     * resulting trace URL. */
    private <T> T traced(String agent, WorkItemRef item, Supplier<T> body) {
        String workflowId = workflowId();
        return tracer.trace(agent, item, null, workflowId, run -> {
            try {
                T result = body.get();
                runs.record(item, agent, workflowId, run.traceUrl(), "ok", null, null);
                return result;
            } catch (RuntimeException e) {
                runs.record(item, agent, workflowId, run.traceUrl(), "error", null, null);
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
