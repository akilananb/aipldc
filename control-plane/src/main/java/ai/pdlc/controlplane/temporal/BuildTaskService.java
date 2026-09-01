package ai.pdlc.controlplane.temporal;

import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.BuildResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.ActivityNotExistsException;
import io.temporal.failure.ApplicationFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns every {@code build_tasks} row — the claimable-DB-row half of async activity completion
 * for {@link BuildActivitiesImpl}. A row is created by {@link #enqueue} when {@code runTask}
 * parks, claimed by the standalone build agent via {@link #claim}, kept alive by {@link
 * #heartbeat} and {@link #pumpHeartbeats} (so an unclaimed or still-in-flight row never trips
 * {@code BUILD_ACTIVITY_OPTIONS}' 2-minute Temporal heartbeat timeout), and finished by {@link
 * #complete} or {@link #fail}, which resolve the parked Temporal activity via {@link
 * ActivityCompletionClient}.
 */
@Service
public class BuildTaskService {

    private static final int LEASE_SECONDS = 90;

    private final JdbcTemplate jdbcTemplate;
    private final ActivityCompletionClient completionClient;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);

    public BuildTaskService(JdbcTemplate jdbcTemplate, ActivityCompletionClient completionClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.completionClient = completionClient;
    }

    /** One claimed row, as returned to the agent — {@code payloadJson} is re-parsed by the
     * controller via {@code ObjectMapper.readTree} to avoid double-encoding. */
    public record ClaimedRow(UUID id, int attempt, String payloadJson) {
    }

    private record TaskRow(UUID id, String state, String taskToken, String claimedBy) {
    }

    /** Parks a fresh {@code pending} row for {@code runTask}'s Temporal task token; a retry (new
     * {@code attempt}) supersedes whatever non-terminal row this profile/story/task already had -
     * its token is stale the instant Temporal schedules a new attempt. */
    public void enqueue(WorkItemRef story, Task task, String branch, String baseBranch, RepoConfig repo, int attempt, byte[] taskToken) {
        jdbcTemplate.update(
                "UPDATE build_tasks SET state='superseded', updated_at=now() "
                        + "WHERE profile=? AND story_board_id=? AND task_id=? AND state IN ('pending','claimed')",
                story.profile(), story.boardId(), task.id());

        String payloadJson = writeJson(Map.of(
                "story", story, "task", task, "branch", branch, "baseBranch", baseBranch, "repo", repo));
        jdbcTemplate.update(
                "INSERT INTO build_tasks (profile, story_board_id, task_id, attempt, payload_json, task_token, state) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'pending')",
                story.profile(), story.boardId(), task.id(), attempt, payloadJson,
                Base64.getEncoder().encodeToString(taskToken));
    }

    /** Atomically claims the oldest matching {@code pending} row for {@code agent}, extending its
     * lease {@link #LEASE_SECONDS}. {@code storyBoardId}/{@code taskId} are optional filters. */
    public Optional<ClaimedRow> claim(String agent, String profile, String storyBoardId, String taskId) {
        List<ClaimedRow> rows = jdbcTemplate.query(
                "UPDATE build_tasks SET state='claimed', claimed_by=?, "
                        + "lease_expires_at=now() + interval '90 seconds', updated_at=now() "
                        + "WHERE id = (SELECT id FROM build_tasks WHERE state='pending' AND profile=? "
                        + "AND (?::text IS NULL OR story_board_id=?) AND (?::text IS NULL OR task_id=?) "
                        + "ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED) "
                        + "RETURNING id, attempt, payload_json",
                (rs, rowNum) -> new ClaimedRow(
                        UUID.fromString(rs.getString("id")), rs.getInt("attempt"), rs.getString("payload_json")),
                agent, profile, storyBoardId, storyBoardId, taskId, taskId);
        return rows.stream().findFirst();
    }

    /** Extends a claimed row's lease and forwards the heartbeat to Temporal. */
    public void heartbeat(UUID id) {
        TaskRow row = requireRow(id);
        requireClaimed(row);
        jdbcTemplate.update(
                "UPDATE build_tasks SET lease_expires_at = now() + interval '90 seconds', updated_at = now() WHERE id = ?",
                id);
        try {
            completionClient.heartbeat(decode(row.taskToken()), "agent:" + row.claimedBy());
        } catch (ActivityNotExistsException | ActivityCanceledException e) {
            markExpired(id);
            throw new BuildTaskGoneException("build task " + id + " is gone", e);
        }
    }

    /** Resolves the parked Temporal activity with {@code result} and marks the row {@code done}.
     * A re-post of an already-{@code done} row is a no-op (idempotent). */
    public void complete(UUID id, BuildResult result) {
        TaskRow row = requireRow(id);
        if ("done".equals(row.state())) {
            return;
        }
        requireClaimed(row);
        try {
            completionClient.complete(decode(row.taskToken()), result);
        } catch (ActivityNotExistsException e) {
            markExpired(id);
            throw new BuildTaskGoneException("build task " + id + " is gone", e);
        }
        jdbcTemplate.update(
                "UPDATE build_tasks SET state='done', result_json=?, updated_at=now() WHERE id=?",
                writeJson(result), id);
    }

    /** Resolves the parked Temporal activity exceptionally (retryable — Temporal re-runs {@code
     * runTask}, producing a fresh {@code pending} row that supersedes this one) and marks the row
     * {@code failed}. */
    public void fail(UUID id, String message) {
        TaskRow row = requireRow(id);
        requireClaimed(row);
        try {
            completionClient.completeExceptionally(
                    decode(row.taskToken()), ApplicationFailure.newFailure(message, "build-agent"));
        } catch (ActivityNotExistsException | ActivityCanceledException e) {
            markExpired(id);
            throw new BuildTaskGoneException("build task " + id + " is gone", e);
        }
        jdbcTemplate.update("UPDATE build_tasks SET state='failed', updated_at=now() WHERE id=?", id);
    }

    /** Keeps every not-yet-dead row's Temporal heartbeat alive — an unclaimed ({@code pending})
     * task is healthy by definition (no agent has picked it up yet), and a {@code claimed} task
     * whose lease has not expired is healthy too; without this, {@code BUILD_ACTIVITY_OPTIONS}'
     * 2-minute heartbeat timeout would kill every task before any agent claims it. A {@code
     * claimed} row whose lease HAS expired is deliberately not forwarded here - a dead agent thus
     * surfaces as a genuine Temporal heartbeat-timeout, triggering retry and supersede. */
    @Scheduled(fixedDelay = 45_000)
    public void pumpHeartbeats() {
        for (TaskRow row : jdbcTemplate.query(
                "SELECT id, state, task_token, claimed_by FROM build_tasks WHERE state='pending'",
                this::mapRow)) {
            forwardPumpHeartbeat(row, "awaiting claim");
        }
        for (TaskRow row : jdbcTemplate.query(
                "SELECT id, state, task_token, claimed_by FROM build_tasks WHERE state='claimed' AND lease_expires_at > now()",
                this::mapRow)) {
            forwardPumpHeartbeat(row, "agent:" + row.claimedBy());
        }
    }

    private void forwardPumpHeartbeat(TaskRow row, String detail) {
        try {
            completionClient.heartbeat(decode(row.taskToken()), detail);
        } catch (ActivityNotExistsException e) {
            markExpired(row.id());
        }
    }

    private TaskRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TaskRow(UUID.fromString(rs.getString("id")), rs.getString("state"), rs.getString("task_token"),
                rs.getString("claimed_by"));
    }

    private TaskRow requireRow(UUID id) {
        List<TaskRow> rows = jdbcTemplate.query(
                "SELECT id, state, task_token, claimed_by FROM build_tasks WHERE id=?", this::mapRow, id);
        if (rows.isEmpty()) {
            throw new ai.pdlc.controlplane.web.NotFoundException("No build task " + id);
        }
        return rows.get(0);
    }

    private void requireClaimed(TaskRow row) {
        if ("done".equals(row.state())) {
            throw new ai.pdlc.controlplane.web.NotFoundException("No build task " + row.id());
        }
        if (!"claimed".equals(row.state())) {
            throw new BuildTaskGoneException("build task " + row.id() + " is " + row.state());
        }
    }

    private void markExpired(UUID id) {
        jdbcTemplate.update("UPDATE build_tasks SET state='expired', updated_at=now() WHERE id=?", id);
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to serialize build task payload", e);
        }
    }
}
