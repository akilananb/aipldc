package ai.pdlc.controlplane.runs;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-process {@link RunStore} for {@link RunServiceTest}; {@link JdbcRunStore} is covered by {@code PlatformRegistryIntegrationTest}. */
class InMemoryRunStore implements RunStore {

    final Map<UUID, RunRow> rows = new ConcurrentHashMap<>();

    @Override
    public boolean insert(RunRow r) {
        if (r.idempotencyKey() != null && findByIdempotencyKey(r.workspaceId(), r.idempotencyKey()).isPresent()) {
            return false;
        }
        rows.put(r.id(), new RunRow(r.id(), r.workspaceId(), r.agentId(), r.agentVersion(), r.contentHash(), r.model(),
                r.providerModel(), r.connectionId(), r.fallback(), r.inputJson(), "QUEUED", null, null, null, null, null,
                0, r.idempotencyKey(), r.workflowId(), r.createdBy(), OffsetDateTime.now(), null, null));
        return true;
    }

    @Override
    public Optional<RunRow> find(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<RunRow> findByIdempotencyKey(String workspaceId, String idempotencyKey) {
        return rows.values().stream()
                .filter(r -> r.workspaceId().equals(workspaceId) && idempotencyKey.equals(r.idempotencyKey()))
                .findFirst();
    }

    @Override
    public List<RunRow> list(String workspaceId, String agentId, int limit) {
        return rows.values().stream().filter(r -> r.workspaceId().equals(workspaceId) && r.agentId().equals(agentId))
                .sorted(Comparator.comparing(RunRow::createdAt).reversed()).limit(limit).toList();
    }

    @Override
    public void failToStart(UUID id, String error) {
        withStatus(id, "FAILED", error);
    }

    void withStatus(UUID id, String status, String error) {
        RunRow r = rows.get(id);
        rows.put(id, new RunRow(r.id(), r.workspaceId(), r.agentId(), r.agentVersion(), r.contentHash(), r.model(),
                r.providerModel(), r.connectionId(), r.fallback(), r.inputJson(), status, r.outputText(), r.outputJson(),
                error, r.promptTokens(), r.completionTokens(), r.attempts(), r.idempotencyKey(), r.workflowId(),
                r.createdBy(), r.createdAt(), r.startedAt(), OffsetDateTime.now()));
    }

    final java.util.Map<UUID, List<ai.pdlc.controlplane.web.dto.ToolCallDto>> toolCalls = new java.util.HashMap<>();

    @Override
    public List<ai.pdlc.controlplane.web.dto.ToolCallDto> toolCalls(UUID runId) {
        return toolCalls.getOrDefault(runId, List.of());
    }

    final Map<UUID, RemoteRow> remotes = new ConcurrentHashMap<>();
    final List<String> replies = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public Optional<RemoteRow> remote(UUID runId) {
        return Optional.ofNullable(remotes.get(runId));
    }

    @Override
    public synchronized Optional<Integer> acceptInput(UUID runId, String text, String by) {
        RunRow r = rows.get(runId);
        if (r == null || !"AWAITING_INPUT".equals(r.status())) {
            return Optional.empty();
        }
        withStatus(runId, "QUEUED", null);
        replies.add(by + ": " + text);
        return Optional.of(replies.size());
    }
}
