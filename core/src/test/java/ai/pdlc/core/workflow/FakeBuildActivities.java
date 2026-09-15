package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.VerifierResult;
import ai.pdlc.core.domain.WorkItemRef;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process fake of the build loop (no real omp/ACP call) for {@link FeatureWorkflowImplTest}. */
class FakeBuildActivities implements BuildActivities {

    final List<String> taskIdsRun = new CopyOnWriteArrayList<>();
    final Map<String, List<String>> feedbackByTask = new ConcurrentHashMap<>();
    boolean returnRed = false;
    /** When true, the first {@code runTask} call for each task id escalates (red + escalation
     * note); every later call for that same task id returns green with no escalation. */
    boolean escalateOnce = false;
    /** When true, the first {@code runTask} call for each task id returns a red verifier result
     * with NO escalation (a legitimate "ran to completion but assertions failed" outcome, distinct
     * from a budget/stuck escalation); every later call for that same task id returns green. */
    boolean redOnce = false;
    private final java.util.Set<String> escalatedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> redOnceSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public BuildResult runTask(WorkItemRef story, Task task, String branch, String baseBranch, List<String> feedback) {
        taskIdsRun.add(task.id());
        feedbackByTask.put(task.id(), feedback);
        boolean escalate = returnRed || (escalateOnce && escalatedOnce.add(task.id()));
        boolean redWithoutEscalation = !escalate && redOnce && redOnceSeen.add(task.id());
        boolean red = escalate || redWithoutEscalation;
        VerifierResult verifier = red
                ? new VerifierResult("red", Map.of(task.scenario(), false), true, "assertion failed")
                : new VerifierResult("green", Map.of(task.scenario(), true), true, "all scenario tests pass");
        String escalation = escalate ? "budget exhausted before green" : null;
        return new BuildResult(task.id(), "deadbeef" + task.id(), verifier, 3, 15_000L, task.touches(),
                "implemented " + task.scenario(), escalation);
    }
}
