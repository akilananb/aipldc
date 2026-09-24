package ai.pdlc.controlplane.runs;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcRunStore implements RunStore {

    private static final RowMapper<RunRow> ROW = (rs, n) -> new RunRow(
            rs.getObject("id", UUID.class), rs.getString("workspace_id"), rs.getString("agent_id"),
            rs.getInt("agent_version"), rs.getString("content_hash"), rs.getString("model"),
            rs.getString("provider_model"), rs.getString("connection_id"), rs.getBoolean("fallback"),
            rs.getString("input_json"), rs.getString("status"), rs.getString("output_text"),
            rs.getString("output_json"), rs.getString("error"), (Integer) rs.getObject("prompt_tokens"),
            (Integer) rs.getObject("completion_tokens"), rs.getInt("attempts"), rs.getString("idempotency_key"),
            rs.getString("workflow_id"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public JdbcRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(RunRow r) {
        try {
            jdbc.update("""
                    INSERT INTO platform_runs (id, workspace_id, agent_id, agent_version, content_hash, model,
                        provider_model, connection_id, fallback, input_json, idempotency_key, workflow_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    r.id(), r.workspaceId(), r.agentId(), r.agentVersion(), r.contentHash(), r.model(),
                    r.providerModel(), r.connectionId(), r.fallback(), r.inputJson(), r.idempotencyKey(),
                    r.workflowId(), r.createdBy());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<RunRow> find(UUID id) {
        return jdbc.query("SELECT * FROM platform_runs WHERE id = ?", ROW, id).stream().findFirst();
    }

    @Override
    public Optional<RunRow> findByIdempotencyKey(String workspaceId, String idempotencyKey) {
        return jdbc.query("SELECT * FROM platform_runs WHERE workspace_id = ? AND idempotency_key = ?", ROW,
                workspaceId, idempotencyKey).stream().findFirst();
    }

    @Override
    public List<RunRow> list(String workspaceId, String agentId, int limit) {
        return jdbc.query("SELECT * FROM platform_runs WHERE workspace_id = ? AND agent_id = ? ORDER BY created_at DESC LIMIT ?",
                ROW, workspaceId, agentId, limit);
    }

    @Override
    public void failToStart(UUID id, String error) {
        jdbc.update("UPDATE platform_runs SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ? AND status = 'QUEUED'",
                error, id);
    }
}
