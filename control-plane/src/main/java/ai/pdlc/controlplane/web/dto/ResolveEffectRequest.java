package ai.pdlc.controlplane.web.dto;

/**
 * An operator's resolution of an UNKNOWN effect: {@code SUCCEEDED} or {@code FAILED} (checked at the
 * target; reported to the model, never resent) or {@code RETRY} (judged safe to send again).
 */
public record ResolveEffectRequest(String outcome, String note) {
}
