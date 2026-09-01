package ai.pdlc.agents.plan;

import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.Task;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Standalone checks for {@link PlanAgent#plan}'s three playbook §3 "Validates" properties —
 * {@link PlanAgent} guarantees these by construction; these are the regression-test surface.
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

    private static int waveIndexOf(PlanHandoff plan, String taskId) {
        for (int i = 0; i < plan.waves().size(); i++) {
            if (plan.waves().get(i).contains(taskId)) {
                return i;
            }
        }
        return -1;
    }
}
