package ai.pdlc.controlplane.web.dto;

/** Optional reason recorded with an approve/reject decision (shown to the model on rejection). */
public record ApprovalDecisionRequest(String reason) {
}
