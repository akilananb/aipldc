package ai.pdlc.controlplane.temporal;

import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.PlanConsultation;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.BuildActivities;
import ai.pdlc.core.workflow.BuildResult;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.springframework.stereotype.Component;

/**
 * Server-side half of {@link BuildActivities} — parks every {@code runTask} invocation as a
 * claimable {@code build_tasks} row (see {@link BuildTaskService#enqueue}) instead of running the
 * build loop in-process; the standalone build agent (a host process with omp/ACP installed) polls
 * control-plane's REST API for the row, runs the build locally, and posts back the result, which
 * completes this activity asynchronously via {@link BuildTaskService#complete}/{@link
 * BuildTaskService#fail}. The target repo is resolved per task/consultation from the story's
 * project, never a process-startup singleton.
 */
@Component
public class BuildActivitiesImpl implements BuildActivities {

    private final BuildTaskService service;
    private final ProjectDirectory projects;

    public BuildActivitiesImpl(BuildTaskService service, ProjectDirectory projects) {
        this.service = service;
        this.projects = projects;
    }

    @Override
    public BuildResult runTask(WorkItemRef story, Task task, String branch, String baseBranch, java.util.List<String> feedback) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        RepoConfig repo = projects.project(story.profile()).repo(task.repo());
        service.enqueue(story, task, branch, baseBranch, feedback, repo, ctx.getInfo().getAttempt(), ctx.getTaskToken());
        ctx.doNotCompleteOnReturn();
        return null; // ignored: completed asynchronously via ActivityCompletionClient
    }

    @Override
    public PlanConsultation.Report consultPlan(WorkItemRef story, PoHandoff po, PlanConsultation consultation) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        RepoConfig repo = projects.project(story.profile()).repo(consultation.repoId());
        service.enqueuePlan(story, po, consultation, repo, ctx.getInfo().getAttempt(), ctx.getTaskToken());
        ctx.doNotCompleteOnReturn();
        return null; // ignored: completed asynchronously via ActivityCompletionClient
    }
}
