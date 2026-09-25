package ai.pdlc.agents.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
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

    private static final ObjectMapper HOSTS = new ObjectMapper();

    /** {@code connection*} fields are null when the connection row is gone. */
    public record PinnedTool(String workspaceId, String toolId, int version, String name, String specJson,
                             String contentHash, String toolStatus, String connectionId, String connectionKind,
                             String connectionStatus, OffsetDateTime connectionExpiresAt, String authType,
                             String secretRef, String baseUrl, boolean granted, String oauthClientId) {

        public PinnedTool(String workspaceId, String toolId, int version, String name, String specJson, String contentHash,
                          String toolStatus, String connectionId, String connectionKind, String connectionStatus,
                          OffsetDateTime connectionExpiresAt, String authType, String secretRef, String baseUrl,
                          boolean granted) {
            this(workspaceId, toolId, version, name, specJson, contentHash, toolStatus, connectionId, connectionKind,
                    connectionStatus, connectionExpiresAt, authType, secretRef, baseUrl, granted, null);
        }

        McpCredentials.Connection connection() {
            return new McpCredentials.Connection(connectionId, authType, secretRef, baseUrl, oauthClientId);
        }
    }

    /** A connection as the MCP discovery activity needs it (no grant: control-plane checked it before starting). */
    public record ConnectionInfo(String id, String kind, String status, OffsetDateTime expiresAt, String authType,
                                 String secretRef, String baseUrl, String oauthClientId) {
    }

    /** One trace row; {@code decision} is ALLOWED or DENIED. Never holds credentials or response bodies. */
    public record CallRecord(UUID runId, int attempt, int turn, String callId, String toolId, Integer toolVersion,
                             String argsJson, String argsHash, String decision, String reason, Integer httpStatus,
                             Long durationMs, Long responseBytes, boolean truncated, String error) {
    }

    /** A requested WRITE call's approval (slice 2.2), bound to the tool version and args hash. */
    public record Approval(UUID id, String toolId, int toolVersion, String argsHash, String status, String decidedBy,
                           String reason) {
    }

    /** The recorded intent and outcome of one write, keyed by {@code run:turn:callId}. */
    public record Effect(UUID id, String idempotencyKey, String state, int sendCount, Integer httpStatus,
                         String resultContent, String resolution, String resolvedBy, String note) {
    }

    private final JdbcTemplate jdbc;

    /** A sandbox catalog entry (slice 2.4) as the executor re-reads it at call time. */
    public record SandboxImage(String id, String imageRef, String status, String inputSchemaJson, String outputSchemaJson,
                               String egressHostsJson, int cpuMillis, int memoryMb, int timeoutSeconds) {
    }

    public ToolStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PinnedTool> load(String workspaceId, String toolId, int version) {
        return jdbc.query("""
                SELECT v.workspace_id, v.tool_id, v.version, v.name, v.spec_json, v.content_hash, d.status,
                       c.id AS connection_id, c.kind, c.status AS connection_status, c.expires_at, c.auth_type,
                       c.secret_ref, c.base_url, c.oauth_client_id,
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
                rs.getString("base_url"), rs.getBoolean("granted"), rs.getString("oauth_client_id")), workspaceId, toolId, version)
                .stream().findFirst();
    }

    public void record(CallRecord r) {
        jdbc.update("""
                INSERT INTO platform_tool_calls (run_id, attempt, turn, call_id, tool_id, tool_version, args_json, args_hash,
                    decision, reason, http_status, duration_ms, response_bytes, truncated, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                r.runId(), r.attempt(), r.turn(), r.callId(), r.toolId(), r.toolVersion(), r.argsJson(), r.argsHash(),
                r.decision(), r.reason(), r.httpStatus(), r.durationMs(), r.responseBytes(), r.truncated(), r.error());
    }

    public Optional<SandboxImage> sandboxImage(String id) {
        return jdbc.query("SELECT * FROM sandbox_images WHERE id = ?", (rs, n) -> new SandboxImage(rs.getString("id"),
                rs.getString("image_ref"), rs.getString("status"), rs.getString("input_schema_json"),
                rs.getString("output_schema_json"), rs.getString("egress_hosts_json"), rs.getInt("cpu_millis"),
                rs.getInt("memory_mb"), rs.getInt("timeout_seconds")), id).stream().findFirst();
    }

    public Optional<ConnectionInfo> connection(String connectionId) {
        return jdbc.query("SELECT * FROM connections WHERE id = ?", (rs, n) -> new ConnectionInfo(rs.getString("id"),
                rs.getString("kind"), rs.getString("status"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("auth_type"), rs.getString("secret_ref"), rs.getString("base_url"), rs.getString("oauth_client_id")),
                connectionId).stream().findFirst();
    }

    public Optional<Approval> approval(UUID runId, int turn, String callId) {
        return jdbc.query("""
                SELECT id, tool_id, tool_version, args_hash, status, decided_by, reason FROM platform_approvals
                WHERE run_id = ? AND turn = ? AND call_id = ?""", (rs, n) -> new Approval(rs.getObject("id", UUID.class),
                rs.getString("tool_id"), rs.getInt("tool_version"), rs.getString("args_hash"), rs.getString("status"),
                rs.getString("decided_by"), rs.getString("reason")), runId, turn, callId).stream().findFirst();
    }

    /** Creates the PENDING approval for a call (idempotent per run/turn/call) and returns it. */
    public Approval requestApproval(UUID runId, String workspaceId, int turn, String callId, String toolId, int toolVersion,
                                    String argsJson, String argsHash) {
        jdbc.update("""
                INSERT INTO platform_approvals (id, run_id, workspace_id, turn, call_id, tool_id, tool_version, args_json, args_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (run_id, turn, call_id) DO NOTHING""",
                UUID.randomUUID(), runId, workspaceId, turn, callId, toolId, toolVersion, argsJson, argsHash);
        return approval(runId, turn, callId).orElseThrow();
    }

    public Optional<Effect> effect(String idempotencyKey) {
        return jdbc.query("SELECT * FROM platform_effects WHERE idempotency_key = ?", (rs, n) -> new Effect(
                rs.getObject("id", UUID.class), rs.getString("idempotency_key"), rs.getString("state"), rs.getInt("send_count"),
                (Integer) rs.getObject("http_status"), rs.getString("result_content"), rs.getString("resolution"),
                rs.getString("resolved_by"), rs.getString("note")), idempotencyKey).stream().findFirst();
    }

    /** Records the intent to write before anything is sent (idempotent per key) and returns the row. */
    public Effect intend(UUID runId, UUID approvalId, String toolId, int toolVersion, String argsHash, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO platform_effects (id, run_id, approval_id, tool_id, tool_version, args_hash, idempotency_key, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'INTENDED') ON CONFLICT (idempotency_key) DO NOTHING""",
                UUID.randomUUID(), runId, approvalId, toolId, toolVersion, argsHash, idempotencyKey);
        return effect(idempotencyKey).orElseThrow();
    }

    /** Marked before the request leaves: from here on a crash means the outcome is unknown. */
    public void markSent(UUID effectId) {
        jdbc.update("UPDATE platform_effects SET state = 'SENT', send_count = send_count + 1, updated_at = now() WHERE id = ?",
                effectId);
    }

    public void finish(UUID effectId, String state, Integer httpStatus, String resultContent) {
        jdbc.update("""
                UPDATE platform_effects SET state = ?, http_status = ?, result_content = ?, updated_at = now()
                WHERE id = ?""", state, httpStatus, resultContent, effectId);
    }

    public void markUnknown(UUID effectId) {
        jdbc.update("UPDATE platform_effects SET state = 'UNKNOWN', updated_at = now() WHERE id = ? AND state = 'SENT'",
                effectId);
    }

    static List<String> readHosts(String json) {
        try {
            return json == null ? List.of() : List.of(HOSTS.readValue(json, String[].class));
        } catch (IOException e) {
            return List.of();
        }
    }
}
