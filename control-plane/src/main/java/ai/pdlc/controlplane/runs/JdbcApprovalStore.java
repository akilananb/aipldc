package ai.pdlc.controlplane.runs;

import ai.pdlc.controlplane.web.dto.ApprovalDto;
import ai.pdlc.controlplane.web.dto.EffectDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcApprovalStore implements ApprovalStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT_APPROVAL = """
            SELECT a.*, r.agent_id, r.agent_version, r.created_by AS run_created_by, v.spec_json AS tool_spec_json
            FROM platform_approvals a
            JOIN platform_runs r ON r.id = a.run_id
            LEFT JOIN tool_definition_versions v
              ON v.workspace_id = a.workspace_id AND v.tool_id = a.tool_id AND v.version = a.tool_version
            """;

    private static final RowMapper<ApprovalDto> APPROVAL = (rs, n) -> {
        JsonNode tool = readTree(rs.getString("tool_spec_json"));
        return new ApprovalDto(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("run_id"), rs.getString("agent_id"),
                rs.getInt("agent_version"), rs.getString("run_created_by"), rs.getInt("turn"), rs.getString("call_id"),
                rs.getString("tool_id"), rs.getInt("tool_version"), text(tool, "method"), text(tool, "path"),
                text(tool, "connectionId"), rs.getString("args_json"), rs.getString("args_hash"), rs.getString("status"),
                rs.getObject("requested_at", OffsetDateTime.class), rs.getObject("escalated_at", OffsetDateTime.class),
                rs.getString("decided_by"), rs.getObject("decided_at", OffsetDateTime.class), rs.getString("reason"));
    };

    private static final RowMapper<EffectDto> EFFECT = (rs, n) -> new EffectDto(
            rs.getString("id"), rs.getString("run_id"), rs.getString("approval_id"), rs.getString("tool_id"),
            rs.getInt("tool_version"), rs.getString("idempotency_key"), rs.getString("state"), rs.getInt("send_count"),
            (Integer) rs.getObject("http_status"), rs.getString("resolution"), rs.getString("resolved_by"),
            rs.getObject("resolved_at", OffsetDateTime.class), rs.getString("note"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public JdbcApprovalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ApprovalDto> list(String workspaceId, String status) {
        return status == null
                ? jdbc.query(SELECT_APPROVAL + " WHERE a.workspace_id = ? ORDER BY a.requested_at DESC LIMIT 200", APPROVAL, workspaceId)
                : jdbc.query(SELECT_APPROVAL + " WHERE a.workspace_id = ? AND a.status = ? ORDER BY a.requested_at DESC LIMIT 200",
                        APPROVAL, workspaceId, status);
    }

    @Override
    public Optional<ApprovalDto> find(String workspaceId, UUID id) {
        return jdbc.query(SELECT_APPROVAL + " WHERE a.workspace_id = ? AND a.id = ?", APPROVAL, workspaceId, id).stream().findFirst();
    }

    @Override
    public Optional<String> runWorkspace(UUID runId) {
        return jdbc.query("SELECT workspace_id FROM platform_runs WHERE id = ?", (rs, n) -> rs.getString(1), runId).stream().findFirst();
    }

    @Override
    public Optional<String> workflowId(UUID runId) {
        return jdbc.query("SELECT workflow_id FROM platform_runs WHERE id = ?", (rs, n) -> rs.getString(1), runId).stream().findFirst();
    }

    @Override
    public boolean decide(UUID id, String status, String decidedBy, String reason) {
        return jdbc.update("""
                UPDATE platform_approvals SET status = ?, decided_by = ?, decided_at = now(), reason = ?
                WHERE id = ? AND status = 'PENDING'""", status, decidedBy, reason, id) == 1;
    }

    @Override
    public List<EffectDto> effects(UUID runId) {
        return jdbc.query("SELECT * FROM platform_effects WHERE run_id = ? ORDER BY created_at", EFFECT, runId);
    }

    @Override
    public Optional<EffectDto> effect(UUID runId, UUID effectId) {
        return jdbc.query("SELECT * FROM platform_effects WHERE run_id = ? AND id = ?", EFFECT, runId, effectId).stream().findFirst();
    }

    @Override
    public boolean resolve(UUID effectId, String resolution, String resolvedBy, String note) {
        String state = "RETRY".equals(resolution) ? "INTENDED" : resolution;
        return jdbc.update("""
                UPDATE platform_effects SET state = ?, resolution = ?, resolved_by = ?, resolved_at = now(), note = ?,
                                            updated_at = now()
                WHERE id = ? AND state = 'UNKNOWN'""", state, resolution, resolvedBy, note, effectId) == 1;
    }

    private static JsonNode readTree(String json) {
        try {
            return json == null ? null : JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? null : node.get(field).asText();
    }
}
