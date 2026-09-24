package ai.pdlc.controlplane.temporal;

import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.VerifierResult;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.BuildResult;
import io.temporal.client.ActivityCompletionClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Docker-dependent (Testcontainers Postgres + real Flyway migrations, see AGENTS.md's
 * Docker-unavailable exclusion list) proof of the build-worker pool's lease contract:
 * per-story claim exclusivity ({@link BuildTaskService#claim}), stale-lease auto-release and
 * reclaim ({@link BuildTaskService#releaseExpiredLeases}) up to {@code MAX_CLAIMS}, and
 * per-claim fencing on {@link BuildTaskService#heartbeat}/{@link BuildTaskService#complete}. No
 * Spring context is booted - {@link BuildTaskService} only needs a {@link JdbcTemplate} and a
 * mocked {@link ActivityCompletionClient} (unused by lease-only assertions here).
 */
@Testcontainers
class BuildTaskLeaseTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    private static String stateOf(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT state FROM build_tasks WHERE id=?", String.class, id);
    }

    private static String claimedByOf(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT claimed_by FROM build_tasks WHERE id=?", String.class, id);
    }

    private static String leaseTokenOf(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT lease_token::text FROM build_tasks WHERE id=?", String.class, id);
    }

    private static String taskIdOf(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT task_id FROM build_tasks WHERE id=?", String.class, id);
    }

    private static String storyBoardIdOf(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT story_board_id FROM build_tasks WHERE id=?", String.class, id);
    }

    private static void expireLease(JdbcTemplate jdbc, UUID id) {
        jdbc.update("UPDATE build_tasks SET lease_expires_at = now() - interval '1 second' WHERE id=?", id);
    }

    @Test
    void leaseLifecycleAcrossStoryExclusivityReclaimAndFencing() throws InterruptedException {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        BuildTaskService service = new BuildTaskService(jdbc, mock(ActivityCompletionClient.class));

        WorkItemRef storyA = new WorkItemRef("local", "A-" + UUID.randomUUID());
        WorkItemRef storyB = new WorkItemRef("local", "B-" + UUID.randomUUID());
        RepoConfig repo = new RepoConfig("orders-service", "git", "https://example.test/orders-service.git",
                "main", "openspec", List.of(), true);
        Task t1 = new Task("T1", "Task 1", "brief", "orders", "orders-service", "scenario-1", List.of("src/a.js"),
                "test/a.test.js", new Task.TaskBudget(5, 20_000L, Duration.ofMinutes(10)), List.of());
        Task t2 = new Task("T2", "Task 2", "brief", "orders", "orders-service", "scenario-2", List.of("src/b.js"),
                "test/b.test.js", new Task.TaskBudget(5, 20_000L, Duration.ofMinutes(10)), List.of());

        // Seed: story A -> T1 then T2 (T1 older), story B -> T1. A short sleep between enqueues
        // guarantees distinct created_at ordering (claim() picks the oldest match).
        service.enqueue(storyA, t1, "story/" + storyA.boardId(), "main", List.of(), repo, 1, "tok-a1".getBytes());
        Thread.sleep(5);
        service.enqueue(storyA, t2, "story/" + storyA.boardId(), "main", List.of(), repo, 1, "tok-a2".getBytes());
        Thread.sleep(5);
        service.enqueue(storyB, t1, "story/" + storyB.boardId(), "main", List.of(), repo, 1, "tok-b1".getBytes());

        // (1) claim exclusivity: story A yields only its oldest task, story B its one task, and a
        // third claimant gets nothing even though A/T2 is still pending (A is already held).
        Optional<BuildTaskService.ClaimedRow> claimA = service.claim("w1", "local", null, null);
        assertThat(claimA).isPresent();
        assertThat(claimA.get().claimCount()).isEqualTo(1);
        assertThat(claimA.get().leaseToken()).isNotNull();
        UUID aT1 = claimA.get().id();
        assertThat(taskIdOf(jdbc, aT1)).isEqualTo("T1");
        assertThat(storyBoardIdOf(jdbc, aT1)).isEqualTo(storyA.boardId());

        Optional<BuildTaskService.ClaimedRow> claimB = service.claim("w2", "local", null, null);
        assertThat(claimB).isPresent();
        UUID bT1 = claimB.get().id();
        assertThat(storyBoardIdOf(jdbc, bT1)).isEqualTo(storyB.boardId());

        assertThat(service.claim("w3", "local", null, null)).isEmpty();

        // (2) an expired lease is released back to pending and reclaimable, with a fresh token
        // and an incremented claim_count.
        expireLease(jdbc, aT1);
        service.releaseExpiredLeases();
        assertThat(stateOf(jdbc, aT1)).isEqualTo("pending");
        assertThat(claimedByOf(jdbc, aT1)).isNull();
        assertThat(leaseTokenOf(jdbc, aT1)).isNull();

        Optional<BuildTaskService.ClaimedRow> reclaim1 = service.claim("w3", "local", storyA.boardId(), null);
        assertThat(reclaim1).isPresent();
        assertThat(reclaim1.get().id()).isEqualTo(aT1);
        assertThat(reclaim1.get().claimCount()).isEqualTo(2);
        String oldToken = claimA.get().leaseToken().toString();
        String newToken = reclaim1.get().leaseToken().toString();
        assertThat(newToken).isNotEqualTo(oldToken);

        // (3) heartbeat fencing: the superseded token is gone, the live token works, a missing
        // token is a client error.
        assertThatThrownBy(() -> service.heartbeat(aT1, oldToken)).isInstanceOf(BuildTaskGoneException.class);
        assertThat(service.heartbeat(aT1, newToken)).isEqualTo("w3");
        assertThatThrownBy(() -> service.heartbeat(aT1, null)).isInstanceOf(IllegalArgumentException.class);

        // (4) exhausting MAX_CLAIMS (3) leaves the row expired instead of bouncing forever, and
        // frees story A for its next task.
        expireLease(jdbc, aT1);
        service.releaseExpiredLeases();
        Optional<BuildTaskService.ClaimedRow> reclaim2 = service.claim("w1", "local", storyA.boardId(), null);
        assertThat(reclaim2).isPresent();
        assertThat(reclaim2.get().claimCount()).isEqualTo(3);

        expireLease(jdbc, aT1);
        service.releaseExpiredLeases();
        assertThat(stateOf(jdbc, aT1)).isEqualTo("expired");

        Optional<BuildTaskService.ClaimedRow> claimT2 = service.claim("w2", "local", storyA.boardId(), null);
        assertThat(claimT2).isPresent();
        assertThat(taskIdOf(jdbc, claimT2.get().id())).isEqualTo("T2");

        // (5) complete fencing and idempotency on story B's still-open row.
        String bToken = claimB.get().leaseToken().toString();
        VerifierResult verifier = new VerifierResult("green", Map.of("scenario-1", true), true, "all tests pass");
        BuildResult result = new BuildResult("T1", "deadbeef", verifier, 1, 1_000L, List.of("src/a.js"), "trace", null);

        assertThatThrownBy(() -> service.complete(bT1, "wrong-token", result))
                .isInstanceOf(BuildTaskGoneException.class);
        service.complete(bT1, bToken, result);
        assertThat(stateOf(jdbc, bT1)).isEqualTo("done");

        // idempotent re-post from the same claimant is a no-op, not an error.
        service.complete(bT1, bToken, result);
        assertThat(stateOf(jdbc, bT1)).isEqualTo("done");
    }
}
