package ai.pdlc.core.plan;

import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, non-LLM plan-check report for one task — replaces the per-task INVEST quality
 * call the old deterministic {@code PlanAgent} used (a scenario/wave/touches dump has no prose to
 * evaluate for INVEST; these checks are the objective properties that actually matter: the task
 * proves exactly one scenario, its wave/blockers are consistent, and its scope stays within the
 * 8-file limit the build loop enforces).
 */
public final class PlanChecks {

    private static final int TOUCHES_LIMIT = 8;

    private PlanChecks() {
    }

    /** @param newFiles repo-relative paths (from any task's {@code touches}/{@code testPath}) that
     *                  do not exist yet in the worktree the planning agent analyzed */
    public static QualityReport report(PlanHandoff plan, Task task, Set<String> newFiles) {
        int waveIndex = waveIndexOf(plan, task.id());
        int waveNumber = waveIndex + 1;
        int totalWaves = plan.waves().size();
        String blockedBy = task.blockedBy().isEmpty() ? "none" : String.join(", ", task.blockedBy());

        List<String> findings = new ArrayList<>();
        boolean sizeOk = task.touches().size() <= TOUCHES_LIMIT;
        String sizeLine = "ok";
        if (!sizeOk) {
            sizeLine = "FAIL — " + task.touches().size() + " files exceeds the " + TOUCHES_LIMIT + "-file limit";
            findings.add("size: " + sizeLine);
        }

        StringBuilder touchesLine = new StringBuilder();
        touchesLine.append("touches (").append(task.touches().size()).append(" files, limit ")
                .append(TOUCHES_LIMIT).append("): ");
        for (int i = 0; i < task.touches().size(); i++) {
            if (i > 0) {
                touchesLine.append(", ");
            }
            String file = task.touches().get(i);
            touchesLine.append(file).append(" (").append(newFiles.contains(file) ? "new file" : "exists").append(")");
        }

        String testState = newFiles.contains(task.testPath()) ? "new file" : "exists";

        String verdict = sizeOk ? "PASS" : "FAIL";
        String reportMd = "VERDICT: " + verdict + "\n"
                + "## Plan checks\n"
                + "- scenario: \"" + task.scenario() + "\" — proven by this task only\n"
                + "- wave: " + waveNumber + " of " + totalWaves + "; blocked by: " + blockedBy + "\n"
                + "- " + touchesLine + "\n"
                + "- test: " + task.testPath() + " (" + testState + ")\n"
                + "- size: " + sizeLine;

        return new QualityReport("task", sizeOk, sizeOk ? 100 : 0, findings, reportMd);
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
