package ai.pdlc.controlplane.runs;

import ai.pdlc.controlplane.web.dto.ToolCallDto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for {@code platform_runs} on the control-plane side; {@link JdbcRunStore} in production. */
public interface RunStore {

    record RunRow(UUID id, String workspaceId, String agentId, int agentVersion, String contentHash, String model,
                  String providerModel, String connectionId, boolean fallback, String inputJson, String status,
                  String outputText, String outputJson, String error, Integer promptTokens, Integer completionTokens,
                  int attempts, String idempotencyKey, String workflowId, String createdBy, OffsetDateTime createdAt,
                  OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    /** Returns false when the workspace already has a run with this idempotency key. */
    boolean insert(RunRow row);

    Optional<RunRow> find(UUID id);

    Optional<RunRow> findByIdempotencyKey(String workspaceId, String idempotencyKey);

    List<RunRow> list(String workspaceId, String agentId, int limit);

    /** The run's recorded tool calls in call order (written by the agents worker's ToolExecutor). */
    List<ToolCallDto> toolCalls(UUID runId);

    /** QUEUED → FAILED when the workflow could not even be started. */
    void failToStart(UUID id, String error);
}
