package ai.pdlc.controlplane.web.dto;

/** POST /api/artifacts/{id}/comments body — {@code target: line:13 | scenario:<name>}, playbook
 * comment contract field names. */
public record CommentRequest(String target, String text, String intent, boolean blocking) {
}
