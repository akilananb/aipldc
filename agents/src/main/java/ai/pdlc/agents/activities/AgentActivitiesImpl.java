package ai.pdlc.agents.activities;

import ai.pdlc.agents.grill.GrillAgent;
import ai.pdlc.agents.monitor.MonitorAgent;
import ai.pdlc.agents.plan.PlanAgent;
import ai.pdlc.agents.po.PoAgent;
import ai.pdlc.agents.release.ReleaseAgent;
import ai.pdlc.agents.review.ReviewAgent;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.MetricsPort;
import ai.pdlc.core.workflow.AgentActivities;
import ai.pdlc.core.workflow.BoardCommentEvent;
import ai.pdlc.core.workflow.BuildResult;
import ai.pdlc.core.workflow.StoryDraft;
import io.temporal.activity.Activity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hosts {@link AgentActivities} on the {@code reasoning} queue. Pure reasoning: gathers context
 * best-effort, invokes the grill/PO agents, and records one {@code runs} row per invocation
 * (plan step 6). No board/DB writes other than the {@code runs} insert — board/repo side effects stay
 * in control-plane's {@code BoardSideEffects}.
 */
@Component
public class AgentActivitiesImpl implements AgentActivities {

    private final GrillAgent grillAgent;
    private final PoAgent poAgent;
    private final PlanAgent planAgent;
    private final ReviewAgent reviewAgent;
    private final ReleaseAgent releaseAgent;
    private final MonitorAgent monitorAgent;
    private final MetricsPort metrics;
    private final RunRecorder runs;

    public AgentActivitiesImpl(GrillAgent grillAgent, PoAgent poAgent, PlanAgent planAgent,
                                ReviewAgent reviewAgent, ReleaseAgent releaseAgent, MonitorAgent monitorAgent,
                                MetricsPort metrics, RunRecorder runs) {
        this.grillAgent = grillAgent;
        this.poAgent = poAgent;
        this.planAgent = planAgent;
        this.reviewAgent = reviewAgent;
        this.releaseAgent = releaseAgent;
        this.monitorAgent = monitorAgent;
        this.metrics = metrics;
        this.runs = runs;
    }

    @Override
    public GrillHandoff grillEvaluate(WorkItemRef item, GrillHandoff previous, List<BoardCommentEvent> newComments) {
        try {
            GrillHandoff result = grillAgent.evaluate(item, previous, newComments);
            runs.record(item, "grill", workflowId(), "ok", null, null);
            return result;
        } catch (RuntimeException e) {
            runs.record(item, "grill", workflowId(), "error", null, null);
            throw e;
        }
    }

    @Override
    public StoryDraft poDraft(WorkItemRef item, GrillHandoff grill) {
        try {
            StoryDraft result = poAgent.draft(item, grill);
            runs.record(item, "po", workflowId(), "ok", null, null);
            return result;
        } catch (RuntimeException e) {
            runs.record(item, "po", workflowId(), "error", null, null);
            throw e;
        }
    }

    @Override
    public StoryDraft poRevise(WorkItemRef item, PoHandoff previous, List<Comment> openComments) {
        try {
            StoryDraft result = poAgent.revise(item, previous, openComments);
            runs.record(item, "po", workflowId(), "ok", null, null);
            return result;
        } catch (RuntimeException e) {
            runs.record(item, "po", workflowId(), "error", null, null);
            throw e;
        }
    }

    @Override
    public PlanHandoff planTasks(WorkItemRef story, PoHandoff po) {
        try {
            PlanHandoff result = planAgent.plan(story, po);
            runs.record(story, "plan", workflowId(), "ok", null, null);
            return result;
        } catch (RuntimeException e) {
            runs.record(story, "plan", workflowId(), "error", null, null);
            throw e;
        }
    }

    @Override
    public ReviewHandoff reviewStory(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results) {
        try {
            ReviewHandoff result = reviewAgent.review(story, po, tasks, results);
            runs.record(story, "review", workflowId(), "ok", null, null);
            return result;
        } catch (RuntimeException e) {
            runs.record(story, "review", workflowId(), "error", null, null);
            throw e;
        }
    }

    @Override
    public ReleaseHandoff draftReleasePack(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results, ReviewHandoff review) {
        try {
            ReleaseHandoff result = releaseAgent.draft(story, po, tasks, results, review);
            runs.record(story, "release", workflowId(), "ok", null, null);
            return result;
        } catch (RuntimeException e) {
            runs.record(story, "release", workflowId(), "error", null, null);
            throw e;
        }
    }

    @Override
    public MonitorHandoff evaluateMonitorRules(WorkItemRef story, List<MonitorRule> rules) {
        try {
            MonitorHandoff result = monitorAgent.evaluate(story, rules, metrics);
            runs.record(story, "monitor", workflowId(), "ok", null, null);
            return result;
        } catch (RuntimeException e) {
            runs.record(story, "monitor", workflowId(), "error", null, null);
            throw e;
        }
    }

    private static String workflowId() {
        try {
            return Activity.getExecutionContext().getInfo().getWorkflowId();
        } catch (RuntimeException e) {
            return null; // not inside an activity (e.g. a direct unit test)
        }
    }
}
