package ai.pdlc.core.plan;

import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * playbook §3 Validates — coverage, in-wave conflict, DAG; run by BuildTaskService.completePlan on
 * every agent-produced plan.
 */
public final class PlanValidator {

    private PlanValidator() {
    }

    /** Every scenario in {@code scenarios} has exactly one task proving it. */
    public static boolean coverageOk(PlanHandoff plan, List<String> scenarios) {
        Set<String> proven = new HashSet<>();
        for (Task t : plan.tasks()) {
            if (!proven.add(t.scenario())) {
                return false; // a scenario proven by more than one task
            }
        }
        return proven.equals(new HashSet<>(scenarios));
    }

    /** No two tasks in the same wave touch the same file. */
    public static boolean conflictFree(PlanHandoff plan) {
        for (List<String> wave : plan.waves()) {
            Set<String> seen = new HashSet<>();
            for (String taskId : wave) {
                for (String file : plan.task(taskId).touches()) {
                    if (!seen.add(file)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Every task's {@code blockedBy} ids resolve to a task in a strictly earlier wave. */
    public static boolean dagOk(PlanHandoff plan) {
        for (int waveIndex = 0; waveIndex < plan.waves().size(); waveIndex++) {
            for (String taskId : plan.waves().get(waveIndex)) {
                for (String blockerId : plan.task(taskId).blockedBy()) {
                    int blockerWave = waveIndexOf(plan, blockerId);
                    if (blockerWave < 0 || blockerWave >= waveIndex) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Human-readable violation lines for every coverage/conflict/DAG failure in {@code plan} —
     * empty when the plan is valid. Used to build a plan-rejection message (the build-worker's
     * next planning attempt gets these lines back as feedback).
     */
    public static List<String> violations(PlanHandoff plan, List<String> scenarios) {
        List<String> violations = new ArrayList<>();

        Map<String, List<String>> tasksByScenario = new HashMap<>();
        for (Task t : plan.tasks()) {
            tasksByScenario.computeIfAbsent(t.scenario(), k -> new ArrayList<>()).add(t.id());
            if (!scenarios.contains(t.scenario())) {
                violations.add("coverage: task " + t.id() + " names unknown scenario \"" + t.scenario() + "\"");
            }
        }
        for (String scenario : scenarios) {
            List<String> provers = tasksByScenario.getOrDefault(scenario, List.of());
            if (provers.isEmpty()) {
                violations.add("coverage: scenario \"" + scenario + "\" has no task");
            } else if (provers.size() > 1) {
                violations.add("coverage: scenario \"" + scenario + "\" is proven by more than one task");
            }
        }

        for (int waveIndex = 0; waveIndex < plan.waves().size(); waveIndex++) {
            Set<String> seen = new HashSet<>();
            for (String taskId : plan.waves().get(waveIndex)) {
                for (String file : plan.task(taskId).touches()) {
                    if (!seen.add(file)) {
                        violations.add("conflict: wave " + waveIndex + " has two tasks touching " + file);
                    }
                }
            }
        }

        for (int waveIndex = 0; waveIndex < plan.waves().size(); waveIndex++) {
            for (String taskId : plan.waves().get(waveIndex)) {
                for (String blockerId : plan.task(taskId).blockedBy()) {
                    int blockerWave = waveIndexOf(plan, blockerId);
                    if (blockerWave < 0 || blockerWave >= waveIndex) {
                        violations.add("dag: " + taskId + " is blocked by " + blockerId
                                + " which is not in an earlier wave");
                    }
                }
            }
        }
        return violations;
    }

    private static int waveIndexOf(PlanHandoff plan, String taskId) {
        for (int i = 0; i < plan.waves().size(); i++) {
            if (plan.waves().get(i).contains(taskId)) {
                return i;
            }
        }
        return -1;
    }
}
