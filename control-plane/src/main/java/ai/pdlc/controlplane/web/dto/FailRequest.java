package ai.pdlc.controlplane.web.dto;

/** {@code POST /api/build-tasks/{id}/fail} body. */
public record FailRequest(String message) {
}
