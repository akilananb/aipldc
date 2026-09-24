package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.PlanConsultation;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * The build loop — {@code docs/agent-playbook.md} §4 "Build agent (the loop)", hosted by the
 * {@code build-worker} Node process on task queue {@link TaskQueues#BUILD}. Drives omp (the coding
 * agent, orchestration-decision.md's choice) over ACP against a real {@code git worktree} on
 * {@code branch}, then runs the target repo's own verifier and enforces the task's {@code touches}
 * scope. Heartbeats while omp is running so a stuck/killed worker is detected well before
 * {@code StartToCloseTimeout}.
 */
@ActivityInterface
public interface BuildActivities {

    /** Explicit wire name: Temporal's Java SDK default-capitalizes activity type names
     * ({@code runTask} -> {@code RunTask}) but the Node SDK registers activities under their
     * exported function name verbatim ({@code runTask}) - this is the project's first
     * cross-language activity boundary, so the name must be pinned explicitly on one side.
     *
     * @param feedback reviewer/human/verifier lines a fix round must address; empty on a first attempt */
    @ActivityMethod(name = "runTask")
    BuildResult runTask(WorkItemRef story, Task task, String branch, String baseBranch, List<String> feedback);

    /** One repository-consultation round: parks like {@code runTask}; the build-worker runs the
     * coding agent read-only in a detached worktree pinned to {@code consultation.baseCommit()}
     * (or resolves the default branch itself on the first round) and posts back evidence — never
     * a task list (see build-worker/src/planTask.ts). */
    @ActivityMethod(name = "consultPlan")
    PlanConsultation.Report consultPlan(WorkItemRef story, PoHandoff po, PlanConsultation consultation);
}
