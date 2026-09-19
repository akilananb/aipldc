package ai.pdlc.core.domain;

import java.util.Map;

/**
 * Build-worker → workflow result of the plan step: the validated plan plus one deterministic
 * plan-check report per task id ({@code subjectKind} {@code "task"}, {@code version} 1 in
 * {@code quality_reports} — see {@link ai.pdlc.core.plan.PlanChecks}).
 *
 * @param plan            the validated plan (coverage/conflict/DAG already checked)
 * @param checksByTaskId  task id → its deterministic plan-check report
 */
public record PlanResult(PlanHandoff plan, Map<String, QualityReport> checksByTaskId) {
    public PlanResult {
        checksByTaskId = checksByTaskId == null ? Map.of() : Map.copyOf(checksByTaskId);
    }
}
