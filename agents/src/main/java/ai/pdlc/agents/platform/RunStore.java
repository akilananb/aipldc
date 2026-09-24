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
                             String secretRef, String baseUrl, int attempts, long activeMs) {
    }

    /** One stored conversation entry (slice 2.2): {@code kind} is USER, ASSISTANT or TOOL_RESULT. */
    public record StoredMessage(int seq, String kind, String contentJson) {
    }

    static final String WAITING_STATES = "'AWAITING_APPROVAL', 'NEEDS_OPERATOR'";

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
                       c.status AS connection_status, c.expires_at, c.auth_type, c.secret_ref, c.base_url
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
                rs.getString("secret_ref"), rs.getString("base_url"), rs.getInt("attempts"), rs.getLong("active_ms")), runId);
        return rows.stream().findFirst();
    }

    /**
     * QUEUED/RUNNING/paused → RUNNING (a retry or a resume re-enters RUNNING); false if the run is
     * already terminal.
     */
    public boolean markRunning(UUID runId) {
        return jdbc.update("""
                UPDATE platform_runs SET status = 'RUNNING', attempts = attempts + 1, started_at = COALESCE(started_at, now())
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING', 'AWAITING_APPROVAL', 'NEEDS_OPERATOR')""", runId) == 1;
    }

    /** RUNNING → AWAITING_APPROVAL or NEEDS_OPERATOR; false if the run left RUNNING meanwhile (e.g. cancelled). */
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
