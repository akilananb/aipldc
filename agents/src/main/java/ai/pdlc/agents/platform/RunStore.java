package ai.pdlc.agents.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The runner's side of the runs module (docs/phase-1-execution-spec.md slice 4): reads a run's
 * pinned definition and model binding from the shared Postgres, and records the outcome. Every
 * write is status-guarded - a run only ever leaves a non-terminal state - so a retried activity,
 * a late model reply and a cancellation can never overwrite one another.
 */
@Repository
public class RunStore {

    /** Everything one invocation needs, joined from the run, its pinned version, model and connection. */
    public record Invocation(UUID runId, String workspaceId, String status, String agentId, int version,
                             String contentHash, String name, String specJson, Map<String, String> inputs,
                             String model, String providerModel, Boolean modelEnabled, String connectionId,
                             String connectionStatus, OffsetDateTime connectionExpiresAt, String authType,
                             String secretRef, String baseUrl, int attempts, long activeMs, String connectionKind,
                             String oauthClientId, boolean granted) {

        /** Native-run shape (a model-provider connection, which is never granted per workspace). */
        public Invocation(UUID runId, String workspaceId, String status, String agentId, int version, String contentHash,
                          String name, String specJson, Map<String, String> inputs, String model, String providerModel,
                          Boolean modelEnabled, String connectionId, String connectionStatus, OffsetDateTime connectionExpiresAt,
                          String authType, String secretRef, String baseUrl, int attempts, long activeMs) {
            this(runId, workspaceId, status, agentId, version, contentHash, name, specJson, inputs, model, providerModel,
                    modelEnabled, connectionId, connectionStatus, connectionExpiresAt, authType, secretRef, baseUrl, attempts,
                    activeMs, "MODEL_PROVIDER", null, false);
        }
    }

    /** The remote task an a2a run follows (slice 2.5); {@code cancel} is what the remote agent did with a cancel. */
    public record RemoteTask(String dialect, String taskId, String contextId, String state, String statusText, String cancel) {
    }

    /** One stored conversation entry (slice 2.2): {@code kind} is USER, ASSISTANT or TOOL_RESULT. */
    public record StoredMessage(int seq, String kind, String contentJson) {
    }

    static final String WAITING_STATES = "'AWAITING_APPROVAL', 'NEEDS_OPERATOR', 'AWAITING_INPUT', 'AWAITING_AUTH'";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public RunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Invocation> load(UUID runId) {
        List<Invocation> rows = jdbc.query("""
                SELECT r.id, r.workspace_id, r.status, r.attempts, r.active_ms, r.agent_id, r.agent_version, r.content_hash, r.input_json,
                       r.model, r.provider_model, r.connection_id,
                       v.name, v.spec_json,
                       m.enabled AS model_enabled,
                       c.status AS connection_status, c.expires_at, c.auth_type, c.secret_ref, c.base_url,
                       c.kind AS connection_kind, c.oauth_client_id,
                       EXISTS (SELECT 1 FROM connection_grants g
                               WHERE g.connection_id = c.id AND g.workspace_id = r.workspace_id) AS granted
                FROM platform_runs r
                JOIN agent_definition_versions v
                  ON v.workspace_id = r.workspace_id AND v.agent_id = r.agent_id AND v.version = r.agent_version
                JOIN connections c ON c.id = r.connection_id
                LEFT JOIN models m ON m.id = r.model
                WHERE r.id = ?""", (rs, n) -> new Invocation(
                rs.getObject("id", UUID.class), rs.getString("workspace_id"), rs.getString("status"),
                rs.getString("agent_id"), rs.getInt("agent_version"), rs.getString("content_hash"),
                rs.getString("name"), rs.getString("spec_json"), readInputs(rs.getString("input_json")),
                rs.getString("model"), rs.getString("provider_model"), (Boolean) rs.getObject("model_enabled"),
                rs.getString("connection_id"), rs.getString("connection_status"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getString("auth_type"),
                rs.getString("secret_ref"), rs.getString("base_url"), rs.getInt("attempts"), rs.getLong("active_ms"),
                rs.getString("connection_kind"), rs.getString("oauth_client_id"), rs.getBoolean("granted")), runId);
        return rows.stream().findFirst();
    }

    /**
     * QUEUED/RUNNING/paused → RUNNING (a retry or a resume re-enters RUNNING); false if the run is
     * already terminal.
     */
    public boolean markRunning(UUID runId) {
        return jdbc.update("""
                UPDATE platform_runs SET status = 'RUNNING', attempts = attempts + 1, started_at = COALESCE(started_at, now())
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING', """ + WAITING_STATES + ")", runId) == 1;
    }

    /** RUNNING → a waiting status; false if the run left RUNNING meanwhile (e.g. cancelled). */
    public boolean pause(UUID runId, String status) {
        return jdbc.update("UPDATE platform_runs SET status = ? WHERE id = ? AND status = 'RUNNING'", status, runId) == 1;
    }

    /** Active (non-waiting) time spent by one invocation segment, counted against the agent's timeout. */
    public void addActiveMs(UUID runId, long millis) {
        jdbc.update("UPDATE platform_runs SET active_ms = active_ms + ? WHERE id = ?", millis, runId);
    }

    public List<StoredMessage> messages(UUID runId) {
        return jdbc.query("SELECT seq, kind, content_json FROM platform_run_messages WHERE run_id = ? ORDER BY seq",
                (rs, n) -> new StoredMessage(rs.getInt("seq"), rs.getString("kind"), rs.getString("content_json")), runId);
    }

    /** Appends at {@code seq}; false if that position is already taken (another attempt got there first). */
    public boolean appendMessage(UUID runId, int seq, String kind, String contentJson) {
        return jdbc.update("""
                INSERT INTO platform_run_messages (run_id, seq, kind, content_json) VALUES (?, ?, ?, ?)
                ON CONFLICT (run_id, seq) DO NOTHING""", runId, seq, kind, contentJson) == 1;
    }

    /** RUNNING → SUCCEEDED; false if the run was cancelled or failed meanwhile (the result is discarded). */
    public boolean complete(UUID runId, String outputText, String outputJson, Integer promptTokens, Integer completionTokens) {
        return jdbc.update("""
                UPDATE platform_runs SET status = 'SUCCEEDED', output_text = ?, output_json = ?, prompt_tokens = ?,
                                         completion_tokens = ?, error = NULL, finished_at = now()
                WHERE id = ? AND status = 'RUNNING'""", outputText, outputJson, promptTokens, completionTokens, runId) == 1;
    }

    /** Non-terminal → FAILED, keeping any model output that failed validation for inspection. */
    public boolean fail(UUID runId, String error, String outputText) {
        boolean failed = jdbc.update("""
                UPDATE platform_runs SET status = 'FAILED', error = ?, output_text = COALESCE(?, output_text), finished_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING', """ + WAITING_STATES + ")", error, outputText, runId) == 1;
        cancelPendingApprovals(runId);
        return failed;
    }

    /** Non-terminal → CANCELLED; its pending approvals can no longer be decided. */
    public boolean cancel(UUID runId) {
        boolean cancelled = jdbc.update("""
                UPDATE platform_runs SET status = 'CANCELLED', finished_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING', """ + WAITING_STATES + ")", runId) == 1;
        cancelPendingApprovals(runId);
        return cancelled;
    }

    private void cancelPendingApprovals(UUID runId) {
        jdbc.update("UPDATE platform_approvals SET status = 'CANCELLED', decided_at = now() WHERE run_id = ? AND status = 'PENDING'",
                runId);
    }

    public Optional<RemoteTask> remoteTask(UUID runId) {
        return jdbc.query("SELECT * FROM platform_remote_tasks WHERE run_id = ?", (rs, n) -> new RemoteTask(rs.getString("dialect"),
                rs.getString("task_id"), rs.getString("context_id"), rs.getString("state"), rs.getString("status_text"),
                rs.getString("cancel")), runId).stream().findFirst();
    }

    /** Records the remote task as last seen; the task id, once known, is never replaced by a null. */
    public void saveRemoteTask(UUID runId, String dialect, String taskId, String contextId, String state, String statusText) {
        jdbc.update("""
                INSERT INTO platform_remote_tasks (run_id, dialect, task_id, context_id, state, status_text) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id) DO UPDATE SET dialect = EXCLUDED.dialect,
                    task_id = COALESCE(platform_remote_tasks.task_id, EXCLUDED.task_id),
                    context_id = COALESCE(EXCLUDED.context_id, platform_remote_tasks.context_id),
                    state = EXCLUDED.state, status_text = EXCLUDED.status_text, updated_at = now()""",
                runId, dialect, taskId, contextId, state, statusText);
    }

    public void setRemoteCancel(UUID runId, String cancel) {
        jdbc.update("UPDATE platform_remote_tasks SET cancel = ?, updated_at = now() WHERE run_id = ?", cancel, runId);
    }

    /** INTENDED/SENT/ACKED, or null when this message was never recorded. */
    public String sendState(UUID runId, String messageId) {
        return jdbc.query("SELECT state FROM platform_remote_sends WHERE run_id = ? AND message_id = ?",
                (rs, n) -> rs.getString("state"), runId, messageId).stream().findFirst().orElse(null);
    }

    /** Records the intent to send (idempotent) and returns the state now on record. */
    public String intendSend(UUID runId, String messageId) {
        jdbc.update("""
                INSERT INTO platform_remote_sends (run_id, message_id, state) VALUES (?, ?, 'INTENDED')
                ON CONFLICT (run_id, message_id) DO NOTHING""", runId, messageId);
        return sendState(runId, messageId);
    }

    public void setSendState(UUID runId, String messageId, String state) {
        jdbc.update("UPDATE platform_remote_sends SET state = ?, updated_at = now() WHERE run_id = ? AND message_id = ?",
                state, runId, messageId);
    }

    /** Records the escalation of a still-pending approval; returns its status either way. */
    public String escalateApproval(UUID approvalId) {
        jdbc.update("UPDATE platform_approvals SET escalated_at = COALESCE(escalated_at, now()) WHERE id = ? AND status = 'PENDING'",
                approvalId);
        return approvalStatus(approvalId);
    }

    /** PENDING → EXPIRED; returns the status afterwards (a decision that won the race stays). */
    public String expireApproval(UUID approvalId) {
        jdbc.update("""
                UPDATE platform_approvals SET status = 'EXPIRED', decided_at = now(), reason = 'expired without a decision'
                WHERE id = ? AND status = 'PENDING'""", approvalId);
        return approvalStatus(approvalId);
    }

    private String approvalStatus(UUID approvalId) {
        return jdbc.query("SELECT status FROM platform_approvals WHERE id = ?", (rs, n) -> rs.getString(1), approvalId)
                .stream().findFirst().orElse("MISSING");
    }

    private Map<String, String> readInputs(String json) {
        try {
            return json == null ? Map.of() : mapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
