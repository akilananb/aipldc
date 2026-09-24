package ai.pdlc.controlplane.web.dto;

import java.util.List;

/** Body of {@code POST /api/build-tasks/{id}/plan-result} — the build-worker consultant's
 * validated {@code .pdlc/consultation.json} evidence for one repository-consultation round: the
 * worker-observed commit SHA, findings markdown, and a file-existence catalog (never an
 * LLM-asserted flag — {@code exists} is filesystem-observed by the worker). Replaces the old
 * final-task-list {@code PlanResultRequest}: the build-worker never publishes tasks directly, only
 * evidence for the Plan Agent (agents module) to reason over. */
public record PlanConsultationResultRequest(String baseCommit, String findingsMarkdown,
                                             List<FileEvidenceRequest> files) {
    public record FileEvidenceRequest(String path, Boolean exists) {
    }
}
