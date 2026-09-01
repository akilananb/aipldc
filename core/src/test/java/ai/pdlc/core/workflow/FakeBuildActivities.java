package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.VerifierResult;
import ai.pdlc.core.domain.WorkItemRef;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process fake of the build loop (no real omp/ACP call) for {@link FeatureWorkflowImplTest}. */
class FakeBuildActivities implements BuildActivities {

    final List<String> taskIdsRun = new CopyOnWriteArrayList<>();
    boolean returnRed = false;

    @Override
    public BuildResult runTask(WorkItemRef story, Task task, String branch, String baseBranch) {
        taskIdsRun.add(task.id());
        VerifierResult verifier = returnRed
                ? new VerifierResult("red", Map.of(task.scenario(), false), true, "assertion failed")
                : new VerifierResult("green", Map.of(task.scenario(), true), true, "all scenario tests pass");
        String escalation = returnRed ? "budget exhausted before green" : null;
        return new BuildResult(task.id(), "deadbeef" + task.id(), verifier, 3, 15_000L, task.touches(),
                "implemented " + task.scenario(), escalation);
    }
}
