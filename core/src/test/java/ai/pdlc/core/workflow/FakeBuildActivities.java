package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.PlanResult;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.VerifierResult;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.plan.PlanChecks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
    final AtomicInteger planCalls = new AtomicInteger();

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

    @Override
    public PlanResult planTasks(WorkItemRef story, PoHandoff po) {
        planCalls.incrementAndGet();
        Task t1 = new Task("T1", "Rate limit on /export",
                "Add the 11th-export 429 path in src/export.js; assert in test/export.test.js.",
                "orders-service/export", "rate-limit", List.of("src/export.js"), "test/export.test.js",
                new Task.TaskBudget(6, 120_000L, Duration.ofMinutes(10)), List.of());
        Handoff envelope = new Handoff("plan-agent", "build-worker", story.boardId(),
                CanonicalState.PLANNED, List.of(), 0.9, List.of(), List.of());
        PlanHandoff plan = new PlanHandoff(envelope, List.of(t1), List.of(List.of("T1")));
        return new PlanResult(plan, Map.of("T1", PlanChecks.report(plan, t1, Set.of())));
    }
}
