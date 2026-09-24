package ai.pdlc.agents.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The executor's view of tools (docs/phase-2-execution-spec.md slice 2.1): a pinned tool version
 * joined with the tool's status, its connection and whether that connection is granted to the
 * workspace - read fresh for every call so a retire, revoke or un-grant applies to the next call
 * of a run already in flight - and the append-only tool-call trace.
 */
@Repository
public class ToolStore {

    /** {@code connection*} fields are null when the connection row is gone. */
    public record PinnedTool(String workspaceId, String toolId, int version, String name, String specJson,
                             String contentHash, String toolStatus, String connectionId, String connectionKind,
                             String connectionStatus, OffsetDateTime connectionExpiresAt, String authType,
                             String secretRef, String baseUrl, boolean granted) {
    }

    /** One trace row; {@code decision} is ALLOWED or DENIED. Never holds credentials or response bodies. */
    public record CallRecord(UUID runId, int attempt, int turn, String callId, String toolId, Integer toolVersion,
                             String argsJson, String argsHash, String decision, String reason, Integer httpStatus,
                             Long durationMs, Long responseBytes, boolean truncated, String error) {
    }

    private final JdbcTemplate jdbc;

    public ToolStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PinnedTool> load(String workspaceId, String toolId, int version) {
        return jdbc.query("""
                SELECT v.workspace_id, v.tool_id, v.version, v.name, v.spec_json, v.content_hash, d.status,
                       c.id AS connection_id, c.kind, c.status AS connection_status, c.expires_at, c.auth_type,
                       c.secret_ref, c.base_url,
                       EXISTS (SELECT 1 FROM connection_grants g
                               WHERE g.connection_id = c.id AND g.workspace_id = v.workspace_id) AS granted
                FROM tool_definition_versions v
                JOIN tool_definitions d ON d.workspace_id = v.workspace_id AND d.id = v.tool_id
                LEFT JOIN connections c ON c.id = (v.spec_json::jsonb ->> 'connectionId')
                WHERE v.workspace_id = ? AND v.tool_id = ? AND v.version = ?""", (rs, n) -> new PinnedTool(
                rs.getString("workspace_id"), rs.getString("tool_id"), rs.getInt("version"), rs.getString("name"),
                rs.getString("spec_json"), rs.getString("content_hash"), rs.getString("status"),
                rs.getString("connection_id"), rs.getString("kind"), rs.getString("connection_status"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getString("auth_type"), rs.getString("secret_ref"),
                rs.getString("base_url"), rs.getBoolean("granted")), workspaceId, toolId, version).stream().findFirst();
    }

    public void record(CallRecord r) {
        jdbc.update("""
                INSERT INTO platform_tool_calls (run_id, attempt, turn, call_id, tool_id, tool_version, args_json, args_hash,
                    decision, reason, http_status, duration_ms, response_bytes, truncated, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                r.runId(), r.attempt(), r.turn(), r.callId(), r.toolId(), r.toolVersion(), r.argsJson(), r.argsHash(),
                r.decision(), r.reason(), r.httpStatus(), r.durationMs(), r.responseBytes(), r.truncated(), r.error());
    }
}
