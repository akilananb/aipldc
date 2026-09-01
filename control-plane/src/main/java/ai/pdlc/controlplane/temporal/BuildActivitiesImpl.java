package ai.pdlc.controlplane.temporal;

import ai.pdlc.core.config.Profile;
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
 * BuildTaskService#fail}.
 */
@Component
public class BuildActivitiesImpl implements BuildActivities {

    private final BuildTaskService service;
    private final Profile activeProfile;

    public BuildActivitiesImpl(BuildTaskService service, Profile activeProfile) {
        this.service = service;
        this.activeProfile = activeProfile;
    }

    @Override
    public BuildResult runTask(WorkItemRef story, Task task, String branch, String baseBranch) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        service.enqueue(story, task, branch, baseBranch, activeProfile.repo(), ctx.getInfo().getAttempt(), ctx.getTaskToken());
        ctx.doNotCompleteOnReturn();
        return null; // ignored: completed asynchronously via ActivityCompletionClient
    }
}
