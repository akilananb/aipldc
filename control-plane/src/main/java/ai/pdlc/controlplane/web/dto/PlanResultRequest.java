package ai.pdlc.controlplane.web.dto;

import java.util.List;

/** Body of {@code POST /api/build-tasks/{id}/plan-result} — the build-worker's planning agent's
 * validated {@code .pdlc/plan.json}, plus which of the referenced paths don't exist yet in the
 * worktree it analyzed (used to annotate the plan-check report). */
public record PlanResultRequest(List<PlannedTask> tasks, List<String> newFiles) {
    public record PlannedTask(String id, String title, String description, String area, String scenario,
                               List<String> touches, String testPath, List<String> blockedBy) {
    }
}
