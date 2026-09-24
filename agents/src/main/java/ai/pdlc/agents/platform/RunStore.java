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
                             String secretRef, String baseUrl) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public RunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Invocation> load(UUID runId) {
        List<Invocation> rows = jdbc.query("""
                SELECT r.id, r.workspace_id, r.status, r.agent_id, r.agent_version, r.content_hash, r.input_json,
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
                rs.getString("secret_ref"), rs.getString("base_url")), runId);
        return rows.stream().findFirst();
    }

    /** QUEUED/RUNNING → RUNNING (a retry re-enters RUNNING); false if the run is already terminal. */
    public boolean markRunning(UUID runId) {
        return jdbc.update("""
                UPDATE platform_runs SET status = 'RUNNING', attempts = attempts + 1, started_at = COALESCE(started_at, now())
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')""", runId) == 1;
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
        return jdbc.update("""
                UPDATE platform_runs SET status = 'FAILED', error = ?, output_text = COALESCE(?, output_text), finished_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')""", error, outputText, runId) == 1;
    }

    public boolean cancel(UUID runId) {
        return jdbc.update("""
                UPDATE platform_runs SET status = 'CANCELLED', finished_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')""", runId) == 1;
    }

    private Map<String, String> readInputs(String json) {
        try {
            return json == null ? Map.of() : mapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
