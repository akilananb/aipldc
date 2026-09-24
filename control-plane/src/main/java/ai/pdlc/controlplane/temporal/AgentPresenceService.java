package ai.pdlc.controlplane.temporal;

import ai.pdlc.controlplane.web.dto.ClaimRequest;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.workflow.TaskQueues;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.StatusRuntimeException;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.PollerInfo;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registry of every agent process the control plane has ever seen — ACP build-workers (fed by
 * every {@code POST /api/build-tasks/claim} poll and {@code /heartbeat}, via {@link
 * #touchAcpOnClaim}/{@link #touchAcpOnHeartbeat}) and reasoning workers (fed by {@link
 * #refreshReasoningPresence}, a scheduled Temporal {@code DescribeTaskQueue} probe of {@link
 * TaskQueues#REASONING}). Deliberately not an HTTP heartbeat from the {@code agents} module —
 * that module must never depend on control-plane (see {@code TaskQueues} Javadoc on the queue
 * split); Temporal's own poller bookkeeping is reused instead. Rows are never deleted, so an
 * agent that stops polling simply ages past {@link #ONLINE_WINDOW} and shows up offline rather
 * than disappearing.
 */
@Service
public class AgentPresenceService {
    private static final Logger log = LoggerFactory.getLogger(AgentPresenceService.class);

    /** One window for both kinds: an idle ACP worker polls every ~5s, a busy one heartbeats
     * every ~30s, and a reasoning long-poll lasts at most ~60s — 90s clears all three with
     * margin without flapping an agent offline between polls. */
    public static final Duration ONLINE_WINDOW = Duration.ofSeconds(90);

    private static final TypeReference<List<ClaimRequest.RepoPresence>> REPOS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowServiceStubs stubs;
    private final String namespace;
    private final ai.pdlc.core.config.ProjectDirectory projects;
    private final String defaultProjectId;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentPresenceService(JdbcTemplate jdbcTemplate, WorkflowServiceStubs stubs,
                                 @Value("${temporal.namespace}") String namespace,
                                 ai.pdlc.core.config.ProjectDirectory projects,
                                 @Value("${pdlc.active-profile}") String defaultProjectId) {
        this.jdbcTemplate = jdbcTemplate;
        this.stubs = stubs;
        this.namespace = namespace;
        this.projects = projects;
        this.defaultProjectId = defaultProjectId;
    }

    public record Presence(String name, String kind, String profile, String acpAgent,
                            List<ClaimRequest.RepoPresence> repos, Integer pollIntervalMs,
                            Instant firstSeenAt, Instant lastSeenAt) {
    }

    public record CurrentTask(UUID claimId, String kind, String storyBoardId, String taskId, String branch,
                               Integer round, Instant since) {
    }

    /** Upserts the ACP worker row for one claim poll. {@code request.presence()} is optional —
     * an older worker or {@code pdlc-assist} posts none, and the row still gets a fresh {@code
     * last_seen_at} with no repo details. */
    public void touchAcpOnClaim(String profile, ClaimRequest request) {
        ClaimRequest.Presence presence = request.presence();
        String acpAgent = presence == null ? null : presence.acpAgent();
        Integer pollIntervalMs = presence == null ? null : presence.pollIntervalMs();
        String reposJson = writeRepos(presence == null ? List.of() : presence.repos());
        jdbcTemplate.update(
                "INSERT INTO agent_presence (name, kind, profile, acp_agent, repos_json, poll_interval_ms) "
                        + "VALUES (?, 'acp', ?, ?, ?, ?) "
                        + "ON CONFLICT (name, kind) DO UPDATE SET profile=EXCLUDED.profile, acp_agent=EXCLUDED.acp_agent, "
                        + "repos_json=EXCLUDED.repos_json, poll_interval_ms=EXCLUDED.poll_interval_ms, last_seen_at=now()",
                request.agent(), profile, acpAgent, reposJson, pollIntervalMs);
    }

    /** Refreshes {@code last_seen_at} for an ACP worker's task heartbeat; a miss (0 rows) is
     * harmless — the row reappears on the worker's next claim poll. */
    public void touchAcpOnHeartbeat(String agentName) {
        jdbcTemplate.update("UPDATE agent_presence SET last_seen_at=now() WHERE name=? AND kind='acp'", agentName);
    }

    /** Probes the reasoning activity queue's pollers every 15s and upserts one row per poller
     * identity, reporting the default project's primary repo. Never propagates — a Temporal
     * outage must not crash the scheduler thread. */
    @Scheduled(fixedDelay = 15_000)
    public void refreshReasoningPresence() {
        try {
            Profile project = projects.project(defaultProjectId);
            RepoConfig primary = project.repo();
            String reposJson = writeRepos(List.of(new ClaimRequest.RepoPresence(
                    primary.id(), "configured", primary.url(), primary.defaultBranch())));
            DescribeTaskQueueResponse response = stubs.blockingStub().describeTaskQueue(
                    DescribeTaskQueueRequest.newBuilder()
                            .setNamespace(namespace)
                            .setTaskQueue(TaskQueue.newBuilder().setName(TaskQueues.REASONING).build())
                            .setTaskQueueType(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY)
                            .build());
            for (PollerInfo poller : response.getPollersList()) {
                Instant lastAccess = Instant.ofEpochSecond(
                        poller.getLastAccessTime().getSeconds(), poller.getLastAccessTime().getNanos());
                jdbcTemplate.update(
                        "INSERT INTO agent_presence (name, kind, profile, acp_agent, repos_json, poll_interval_ms, last_seen_at) "
                                + "VALUES (?, 'reasoning', ?, NULL, ?, NULL, ?) "
                                + "ON CONFLICT (name, kind) DO UPDATE SET profile=EXCLUDED.profile, repos_json=EXCLUDED.repos_json, "
                                + "last_seen_at=GREATEST(agent_presence.last_seen_at, EXCLUDED.last_seen_at)",
                        poller.getIdentity(), defaultProjectId, reposJson, java.sql.Timestamp.from(lastAccess));
            }
        } catch (StatusRuntimeException e) {
            log.warn("[presence] describeTaskQueue reasoning failed: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("[presence] could not resolve default project '{}': {}", defaultProjectId, e.toString());
        }
    }

    public List<Presence> list() {
        return jdbcTemplate.query(
                "SELECT name, kind, profile, acp_agent, repos_json, poll_interval_ms, first_seen_at, last_seen_at "
                        + "FROM agent_presence ORDER BY kind, name",
                this::mapPresence);
    }

    /** The current claimed (non-expired) build task per ACP agent name, keyed by {@code
     * claimed_by}. Single-flight workers hold at most one row; if two exist, the most recently
     * updated wins. */
    public Map<String, CurrentTask> currentAcpTasks() {
        Map<String, CurrentTask> byAgent = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT id, claimed_by, payload_json, updated_at FROM build_tasks "
                        + "WHERE state='claimed' AND lease_expires_at > now() ORDER BY updated_at",
                rs -> {
                    String agent = rs.getString("claimed_by");
                    UUID id = UUID.fromString(rs.getString("id"));
                    Instant since = rs.getTimestamp("updated_at").toInstant();
                    byAgent.put(agent, currentTaskOf(id, rs.getString("payload_json"), since));
                });
        return byAgent;
    }

    /** Parses one {@code build_tasks.payload_json} row into what the agent is currently working
     * on. Package-private and pure so it is unit-testable without a database. Never throws — an
     * unparseable payload degrades to an "unknown" task rather than breaking the whole presence
     * endpoint. */
    static CurrentTask currentTaskOf(UUID claimId, String payloadJson, Instant since) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload;
        try {
            payload = mapper.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            return new CurrentTask(claimId, "unknown", null, null, null, null, since);
        }
        String kind = textOrNull(payload, "kind");
        JsonNode storyNode = payload.get("story");
        String storyBoardId = storyNode == null ? null : textOrNull(storyNode, "boardId");
        if ("plan".equals(kind)) {
            JsonNode consultation = payload.get("consultation");
            Integer round = consultation != null && consultation.hasNonNull("round")
                    ? consultation.get("round").asInt() : null;
            return new CurrentTask(claimId, "plan", storyBoardId, "plan", null, round, since);
        }
        if ("build".equals(kind)) {
            JsonNode taskNode = payload.get("task");
            String taskId = taskNode == null ? null : textOrNull(taskNode, "id");
            String branch = textOrNull(payload, "branch");
            return new CurrentTask(claimId, "build", storyBoardId, taskId, branch, null, since);
        }
        return new CurrentTask(claimId, "unknown", storyBoardId, null, null, null, since);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public static boolean onlineAt(Instant lastSeenAt, Instant now) {
        return !lastSeenAt.plus(ONLINE_WINDOW).isBefore(now);
    }

    private String writeRepos(List<ClaimRequest.RepoPresence> repos) {
        try {
            return mapper.writeValueAsString(repos == null ? List.of() : repos);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<ClaimRequest.RepoPresence> readRepos(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, REPOS_TYPE);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Presence mapPresence(ResultSet rs, int rowNum) throws SQLException {
        return new Presence(
                rs.getString("name"), rs.getString("kind"), rs.getString("profile"), rs.getString("acp_agent"),
                readRepos(rs.getString("repos_json")),
                (Integer) rs.getObject("poll_interval_ms"),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant());
    }
}
