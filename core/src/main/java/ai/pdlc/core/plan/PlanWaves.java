package ai.pdlc.core.plan;

import ai.pdlc.core.domain.Task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sequences an agent-produced task list into waves by {@code blockedBy}/{@code touches} conflict
 * order — playbook §3 "Validates" DAG/no-in-wave-conflict properties, computed here instead of
 * asserted, since the build-worker's planning agent (unlike the old deterministic {@code
 * PlanAgent}) does not construct waves itself.
 */
public final class PlanWaves {

    private PlanWaves() {
    }

    /**
     * @param tasks tasks in dependency order: a task's {@code blockedBy} ids must already appear
     *              earlier in this list
     * @throws IllegalArgumentException a task's {@code blockedBy} names an id not yet placed
     *                                  (unknown, or later in {@code tasks})
     */
    public static List<List<String>> compute(List<Task> tasks) {
        List<List<String>> waves = new ArrayList<>();
        List<Set<String>> waveTouchedFiles = new ArrayList<>();

        for (Task task : tasks) {
            int minWave = 0;
            for (String blockerId : task.blockedBy()) {
                int blockerWave = waveIndexOf(waves, blockerId);
                if (blockerWave < 0) {
                    throw new IllegalArgumentException(
                            task.id() + " blockedBy unknown/later task " + blockerId);
                }
                minWave = Math.max(minWave, blockerWave + 1);
            }

            int w = minWave;
            while (w < waves.size() && intersects(waveTouchedFiles.get(w), task.touches())) {
                w++;
            }
            if (w == waves.size()) {
                waves.add(new ArrayList<>());
                waveTouchedFiles.add(new LinkedHashSet<>());
            }
            waves.get(w).add(task.id());
            waveTouchedFiles.get(w).addAll(task.touches());
        }
        return waves;
    }

    private static boolean intersects(Set<String> a, List<String> b) {
        return b.stream().anyMatch(a::contains);
    }

    private static int waveIndexOf(List<List<String>> waves, String taskId) {
        for (int i = 0; i < waves.size(); i++) {
            if (waves.get(i).contains(taskId)) {
                return i;
            }
        }
        return -1;
    }
}
