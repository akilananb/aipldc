package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A platform run: what it pinned ({@code agentVersion} + {@code contentHash}, model and connection),
 * its inputs, status and outcome. {@code output} is the parsed JSON when the agent declares an
 * {@code outputSchema}. Token counts are {@code null} when the provider did not report them.
 */
public record RunDto(String id, String workspaceId, String agentId, int agentVersion, String contentHash, String model,
                     String providerModel, String connectionId, boolean fallback, Map<String, String> inputs,
                     String status, String outputText, Object output, String error, Integer promptTokens,
                     Integer completionTokens, int attempts, String idempotencyKey, String createdBy,
                     OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
}
