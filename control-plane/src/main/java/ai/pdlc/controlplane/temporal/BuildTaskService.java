package ai.pdlc.controlplane.temporal;

import ai.pdlc.controlplane.web.dto.PlanConsultationResultRequest;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.PlanConsultation;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.plan.PlanAssembler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.ActivityNotExistsException;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /** After this many lost/expired leases, a row is left {@code expired} for Temporal's own
     * heartbeat-timeout retry instead of being handed back to the pool indefinitely. */
    private static final int MAX_CLAIMS = 3;

    private static final Logger log = LoggerFactory.getLogger(BuildTaskService.class);

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
     * controller via {@code ObjectMapper.readTree} to avoid double-encoding. {@code leaseToken}
     * fences every subsequent heartbeat/result/plan-result/fail call for this claim; {@code
     * claimCount} is 1 on a fresh claim and >1 when this row is being reclaimed after a previous
     * holder's lease expired. */
    public record ClaimedRow(UUID id, int attempt, String payloadJson, UUID leaseToken, int claimCount) {
    }

    private record TaskRow(UUID id, String state, String taskToken, String claimedBy, String leaseToken) {
    }

    /** Parks a fresh {@code pending} row for {@code runTask}'s Temporal task token; a retry (new
     * {@code attempt}) supersedes whatever non-terminal row this profile/story/task already had -
     * its token is stale the instant Temporal schedules a new attempt. {@code feedback} is always
     * present in the payload ({@code []} on a first attempt) - reviewer/human/verifier lines a fix
     * round must address. */
    public void enqueue(WorkItemRef story, Task task, String branch, String baseBranch, List<String> feedback, RepoConfig repo, int attempt, byte[] taskToken) {
        jdbcTemplate.update(
                "UPDATE build_tasks SET state='superseded', updated_at=now() "
                        + "WHERE profile=? AND story_board_id=? AND task_id=? AND state IN ('pending','claimed')",
                story.profile(), story.boardId(), task.id());

        String payloadJson = writeJson(Map.of(
                "kind", "build", "story", story, "task", task, "branch", branch, "baseBranch", baseBranch,
                "feedback", feedback == null ? List.of() : feedback, "repo", repo));
        jdbcTemplate.update(
                "INSERT INTO build_tasks (profile, story_board_id, task_id, attempt, payload_json, task_token, state) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'pending')",
                story.profile(), story.boardId(), task.id(), attempt, payloadJson,
                Base64.getEncoder().encodeToString(taskToken));
    }

    /** Parks a fresh {@code pending} row for one repository-consultation round's Temporal task
     * token, under the fixed task id {@code plan} - a story has at most one in-flight plan
     * consultation at a time, so a retry supersedes whatever non-terminal plan row this story
     * already had, same as {@link #enqueue}. {@code attempt} is the Temporal activity retry
     * count, not the consultation round - {@code consultation.round()} carries that. */
    public void enqueuePlan(WorkItemRef story, PoHandoff po, PlanConsultation consultation, RepoConfig repo, int attempt, byte[] taskToken) {
        jdbcTemplate.update(
                "UPDATE build_tasks SET state='superseded', updated_at=now() "
                        + "WHERE profile=? AND story_board_id=? AND task_id=? AND state IN ('pending','claimed')",
                story.profile(), story.boardId(), "plan");

        String payloadJson = writeJson(Map.of(
                "kind", "plan", "story", story, "po", po, "baseBranch", repo.defaultBranch(), "repo", repo,
                "consultation", consultation));
        jdbcTemplate.update(
                "INSERT INTO build_tasks (profile, story_board_id, task_id, attempt, payload_json, task_token, state) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'pending')",
                story.profile(), story.boardId(), "plan", attempt, payloadJson,
                Base64.getEncoder().encodeToString(taskToken));
    }

    /** Atomically claims the oldest matching {@code pending} row for {@code agent}, extending its
     * lease {@link #LEASE_SECONDS} and issuing a fresh per-claim fencing token. First releases any
     * stale leases (see {@link #releaseExpiredLeases}) so a just-died worker's row is reclaimable on
     * this very poll. {@code storyBoardId}/{@code taskId} are optional filters. At most one story can
     * be claimed at a time — wave tasks share one branch (see {@code FeatureWorkflowImpl#runWave}) —
     * enforced by a partial unique index; a losing race for a two-pending-task story simply returns
     * empty, as if nothing matched. */
    public Optional<ClaimedRow> claim(String agent, String profile, String storyBoardId, String taskId) {
        releaseExpiredLeases();
        UUID leaseToken = UUID.randomUUID();
        try {
            List<ClaimedRow> rows = jdbcTemplate.query(
                    "UPDATE build_tasks SET state='claimed', claimed_by=?, lease_token=?, claim_count=claim_count+1, "
                            + "lease_expires_at=now() + interval '90 seconds', updated_at=now() "
                            + "WHERE id = (SELECT t.id FROM build_tasks t "
                            + "WHERE t.state='pending' AND t.profile=? "
                            + "AND (?::text IS NULL OR t.story_board_id=?) AND (?::text IS NULL OR t.task_id=?) "
                            + "AND NOT EXISTS (SELECT 1 FROM build_tasks h "
                            + "WHERE h.profile=t.profile AND h.story_board_id=t.story_board_id AND h.state='claimed') "
                            + "ORDER BY t.created_at LIMIT 1 FOR UPDATE OF t SKIP LOCKED) "
                            + "RETURNING id, attempt, payload_json, lease_token, claim_count",
                    (rs, rowNum) -> new ClaimedRow(
                            UUID.fromString(rs.getString("id")), rs.getInt("attempt"), rs.getString("payload_json"),
                            UUID.fromString(rs.getString("lease_token")), rs.getInt("claim_count")),
                    agent, leaseToken, profile, storyBoardId, storyBoardId, taskId, taskId);
            return rows.stream().findFirst();
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    /** Auto-releases stale claims so any worker reclaims them on its next poll: a {@code claimed}
     * row whose lease has expired is bounced back to {@code pending} (fresh {@code claimed_by}/
     * {@code lease_token}/{@code lease_expires_at}), unless it has already burned through {@link
     * #MAX_CLAIMS} attempts, in which case it is left {@code expired} for Temporal's own
     * heartbeat-timeout retry (the pre-pool supersede path) instead of bouncing forever. Runs on a
     * schedule and defensively at the top of every {@link #claim} so a just-died worker's row is
     * reclaimable within one poll interval, not just the next sweep. */
    @Scheduled(fixedDelay = 15_000)
    public void releaseExpiredLeases() {
        int exhausted = jdbcTemplate.update(
                "UPDATE build_tasks SET state='expired', lease_token=NULL, updated_at=now() "
                        + "WHERE state='claimed' AND lease_expires_at < now() AND claim_count >= ?",
                MAX_CLAIMS);
        int released = jdbcTemplate.update(
                "UPDATE build_tasks SET state='pending', claimed_by=NULL, lease_token=NULL, lease_expires_at=NULL, updated_at=now() "
                        + "WHERE state='claimed' AND lease_expires_at < now()");
        if (released > 0) {
            log.info("[build-tasks] released {} stale lease(s) for reclaim", released);
        }
        if (exhausted > 0) {
            log.warn("[build-tasks] {} task(s) exhausted {} claims, left for Temporal retry", exhausted, MAX_CLAIMS);
        }
    }

    /** One open (not yet terminal) row for a story, as surfaced on the item detail page's live
     * plan/build status — {@code taskId} is the literal {@code "plan"} sentinel for a plan
     * consultation round, or a real task id (e.g. {@code "T1"}) for a build task; {@code round}
     * is only ever non-null for a plan row (see {@link AgentPresenceService#currentTaskOf}). */
    public record OpenTask(String taskId, String state, String claimedBy, Integer round, Instant since) {
    }

    /** The most recently created still-open ({@code pending}/{@code claimed}) {@code build_tasks}
     * row for {@code storyBoardId} - used to show "waiting for a build-worker" / "consulting the
     * repository round N" on the item detail page while nothing has claimed it yet, or while a
     * build-worker is actively working it. */
    public Optional<OpenTask> latestOpenTask(String profile, String storyBoardId) {
        List<OpenTask> rows = jdbcTemplate.query(
                "SELECT id, task_id, state, claimed_by, payload_json, updated_at FROM build_tasks "
                        + "WHERE profile=? AND story_board_id=? AND state IN ('pending','claimed') "
                        + "ORDER BY created_at DESC LIMIT 1",
                (rs, rowNum) -> {
                    UUID id = UUID.fromString(rs.getString("id"));
                    Instant since = rs.getTimestamp("updated_at").toInstant();
                    AgentPresenceService.CurrentTask current =
                            AgentPresenceService.currentTaskOf(id, rs.getString("payload_json"), since);
                    return new OpenTask(rs.getString("task_id"), rs.getString("state"),
                            rs.getString("claimed_by"), current.round(), since);
                },
                profile, storyBoardId);
        return rows.stream().findFirst();
    }

    /** Extends a claimed row's lease, forwards the heartbeat to Temporal, and returns the
     * claiming agent's name so the caller can also refresh its presence row. */
    public String heartbeat(UUID id, String leaseToken) {
        TaskRow row = requireHeld(id, leaseToken);
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
        return row.claimedBy();
    }

    /** Resolves the parked Temporal activity with {@code result} and marks the row {@code done}.
     * A re-post of an already-{@code done} row from the same claimant (matching {@code
     * leaseToken}) is a no-op (idempotent); a stale claimant gets 410 via {@link #requireHeld}. */
    public void complete(UUID id, String leaseToken, Object result) {
        TaskRow row = requireHeld(id, leaseToken);
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

    private static final int MAX_REPORT_FILES = 128;
    private static final int MAX_REPORT_BYTES = 64 * 1024;

    /** Validates the build-worker consultant's posted evidence for one plan-consultation round —
     * nonblank findings, a full hexadecimal git SHA, unique valid file paths (each with an
     * explicit {@code exists} flag), every requested {@code consultation.paths} entry present, a
     * baseCommit matching the round's requested snapshot when one was specified, and report size
     * limits - then resolves the parked plan activity with the resulting {@link
     * PlanConsultation.Report}. Final-plan coverage/scope validation happens later, deterministically,
     * in {@code ai.pdlc.core.plan.PlanAssembler} once the Plan Agent FINALIZEs - this method only
     * ever validates one round's evidence. A rejected report throws {@link IllegalArgumentException}
     * (400) without touching the row - the build-worker posts {@code /fail}, and Temporal retries
     * the plan activity. An already-{@code done} row returns its stored report without completing
     * again (idempotent, matching {@link #complete}). */
    public PlanConsultation.Report completePlan(UUID id, String leaseToken, PlanConsultationResultRequest request) {
        TaskRow row = requireHeld(id, leaseToken);
        if ("done".equals(row.state())) {
            String resultJson = jdbcTemplate.queryForObject(
                    "SELECT result_json FROM build_tasks WHERE id=?", String.class, id);
            return readValue(resultJson, PlanConsultation.Report.class);
        }
        requireClaimed(row);

        String payloadJson = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM build_tasks WHERE id=?", String.class, id);
        JsonNode payload = readTree(payloadJson);
        if (payload.get("kind") == null || !"plan".equals(payload.get("kind").asText())) {
            throw new IllegalArgumentException("plan rejected: claimed row is not a plan consultation");
        }
        JsonNode consultationNode = payload.get("consultation");
        String requestedBaseCommit = consultationNode != null && consultationNode.hasNonNull("baseCommit")
                ? consultationNode.get("baseCommit").asText() : "";
        List<String> requestedPaths = new ArrayList<>();
        if (consultationNode != null && consultationNode.get("paths") != null) {
            for (JsonNode p : consultationNode.get("paths")) {
                requestedPaths.add(p.asText());
            }
        }

        List<String> errors = new ArrayList<>();

        String findings = request.findingsMarkdown();
        if (findings == null || findings.isBlank()) {
            errors.add("findingsMarkdown must be non-blank");
        }

        String baseCommit = request.baseCommit();
        if (baseCommit == null || !isFullHexSha(baseCommit)) {
            errors.add("baseCommit must be a full hexadecimal git SHA (40 or 64 characters)");
        } else if (!requestedBaseCommit.isBlank() && !requestedBaseCommit.equals(baseCommit)) {
            errors.add("baseCommit does not match the requested consultation snapshot " + requestedBaseCommit);
        }

        List<PlanConsultationResultRequest.FileEvidenceRequest> requestFiles =
                request.files() == null ? List.of() : request.files();
        if (requestFiles.size() > MAX_REPORT_FILES) {
            errors.add("report exceeds the " + MAX_REPORT_FILES + " file-entry limit");
        }

        List<PlanConsultation.FileEvidence> files = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (PlanConsultationResultRequest.FileEvidenceRequest fe : requestFiles) {
            String path = fe.path();
            if (path == null || !PlanAssembler.isValidRepoPath(path)) {
                errors.add("invalid file path: " + path);
                continue;
            }
            if (!seenPaths.add(path)) {
                errors.add("duplicate file path: " + path);
                continue;
            }
            if (fe.exists() == null) {
                errors.add(path + ": exists flag is required");
                continue;
            }
            files.add(new PlanConsultation.FileEvidence(path, fe.exists()));
        }
        for (String requested : requestedPaths) {
            if (!seenPaths.contains(requested)) {
                errors.add("missing requested path in report: " + requested);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("plan rejected: " + String.join("; ", errors));
        }

        PlanConsultation.Report report = new PlanConsultation.Report(baseCommit, findings, files);
        if (writeJson(report).getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) {
            throw new IllegalArgumentException("plan rejected: report exceeds the 64 KiB size limit");
        }

        complete(id, leaseToken, report);
        return report;
    }

    private static boolean isFullHexSha(String s) {
        return s.matches("^[0-9a-fA-F]{40}$") || s.matches("^[0-9a-fA-F]{64}$");
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to parse stored build task result", e);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to parse stored build task payload", e);
        }
    }

    /** Resolves the parked Temporal activity exceptionally (retryable — Temporal re-runs {@code
     * runTask}, producing a fresh {@code pending} row that supersedes this one) and marks the row
     * {@code failed}. */
    public void fail(UUID id, String leaseToken, String message) {
        TaskRow row = requireHeld(id, leaseToken);
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
     * claimed} row whose lease has expired is released back to {@code pending} by {@link
     * #releaseExpiredLeases} (up to {@link #MAX_CLAIMS} times) and resumes being forwarded here as
     * awaiting-claim; only a row that has exhausted {@link #MAX_CLAIMS} is left to trip Temporal's
     * own heartbeat timeout. */
    @Scheduled(fixedDelay = 45_000)
    public void pumpHeartbeats() {
        for (TaskRow row : jdbcTemplate.query(
                "SELECT id, state, task_token, claimed_by, lease_token FROM build_tasks WHERE state='pending'",
                this::mapRow)) {
            forwardPumpHeartbeat(row, "awaiting claim");
        }
        for (TaskRow row : jdbcTemplate.query(
                "SELECT id, state, task_token, claimed_by, lease_token FROM build_tasks WHERE state='claimed' AND lease_expires_at > now()",
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
                rs.getString("claimed_by"), rs.getString("lease_token"));
    }

    private TaskRow requireRow(UUID id) {
        List<TaskRow> rows = jdbcTemplate.query(
                "SELECT id, state, task_token, claimed_by, lease_token FROM build_tasks WHERE id=?", this::mapRow, id);
        if (rows.isEmpty()) {
            throw new ai.pdlc.controlplane.web.NotFoundException("No build task " + id);
        }
        return rows.get(0);
    }

    /** Fences every per-claim call (heartbeat/complete/completePlan/fail) behind the caller's
     * {@code X-Lease-Token}: a missing token is a client error (400, via {@link
     * IllegalArgumentException}), a token that doesn't match the row's live {@code lease_token} —
     * because the row was never claimed, or another worker has since reclaimed it — is 410 via
     * {@link BuildTaskGoneException}, same as any other "this row is no longer live" condition. */
    private TaskRow requireHeld(UUID id, String leaseToken) {
        if (leaseToken == null || leaseToken.isBlank()) {
            throw new IllegalArgumentException("X-Lease-Token is required");
        }
        TaskRow row = requireRow(id);
        if (row.leaseToken() == null || !row.leaseToken().equals(leaseToken)) {
            throw new BuildTaskGoneException("build task " + id + " lease is not held by this worker");
        }
        return row;
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
