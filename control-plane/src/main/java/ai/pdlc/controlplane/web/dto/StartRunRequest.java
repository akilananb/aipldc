package ai.pdlc.controlplane.web.dto;

import java.util.Map;

/**
 * Start a run of an agent's current published version. {@code idempotencyKey} (optional, unique
 * per workspace): repeating a start with the same key returns the same run instead of a new one.
 */
public record StartRunRequest(Map<String, String> inputs, String idempotencyKey) {
}
