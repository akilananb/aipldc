package ai.pdlc.controlplane.runs;

import ai.pdlc.controlplane.web.dto.ToolCallDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    private static final ObjectMapper JSON = new ObjectMapper();

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
    public Optional<RemoteRow> remote(UUID runId) {
        return jdbc.query("SELECT * FROM platform_remote_tasks WHERE run_id = ?", (rs, n) -> new RemoteRow(
                rs.getString("dialect"), rs.getString("task_id"), rs.getString("context_id"), rs.getString("state"),
                rs.getString("status_text"), rs.getString("cancel")), runId).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<Integer> acceptInput(UUID runId, String text, String by) {
        if (jdbc.update("UPDATE platform_runs SET status = 'QUEUED' WHERE id = ? AND status = 'AWAITING_INPUT'", runId) != 1) {
            return Optional.empty();
        }
        Integer seq = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) + 1 FROM platform_run_messages WHERE run_id = ?",
                Integer.class, runId);
        jdbc.update("INSERT INTO platform_run_messages (run_id, seq, kind, content_json) VALUES (?, ?, 'REMOTE_USER', ?)",
                runId, seq, JSON.createObjectNode().put("text", text).put("by", by).toString());
        return Optional.ofNullable(seq);
    }

    @Override
    public void failToStart(UUID id, String error) {
        jdbc.update("UPDATE platform_runs SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ? AND status = 'QUEUED'",
                error, id);
    }

    @Override
    public List<ToolCallDto> toolCalls(UUID runId) {
        return jdbc.query("SELECT * FROM platform_tool_calls WHERE run_id = ? ORDER BY id", (rs, n) -> new ToolCallDto(
                rs.getLong("id"),
                rs.getInt("attempt"),
                rs.getInt("turn"),
                rs.getString("tool_id"),
                (Integer) rs.getObject("tool_version"),
                rs.getString("args_json"),
                rs.getString("args_hash"),
                rs.getString("decision"),
                rs.getString("reason"),
                (Integer) rs.getObject("http_status"),
                (Long) rs.getObject("duration_ms"),
                (Long) rs.getObject("response_bytes"),
                rs.getBoolean("truncated"),
                rs.getString("error"),
                rs.getObject("created_at", java.time.OffsetDateTime.class)), runId);
    }
}
