package ai.pdlc.agents.activities;

import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.domain.WorkItemRef;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Appends one row per agent run to the shared {@code runs} table via plain {@link JdbcTemplate}
 * (plan step 6: spring-boot-starter-jdbc only, no spring-data-jdbc). Best-effort: a recording failure
 * never fails the activity it is recording. Also back-fills a minimal {@code work_items} placeholder
 * row so the {@code runs.work_item_id} FK never trips before control-plane has inserted the feature
 * (e.g. the very first {@code grillEvaluate}).
 *
 * <p>Two mechanisms close rows a crashed/killed worker would otherwise leave stuck at {@code
 * running} (stuck-agent-run-banner plan steps 2-3): {@link #start}/{@link #startById} supersede
 * any still-{@code running} row for the same (item, agent, phase) before inserting a fresh one —
 * a Temporal retry always starts a new row, so a prior {@code running} row for that key is by
 * definition an orphan of a dead attempt; and {@link #abandonInFlight()} marks every row this
 * process itself started but never finished as {@code abandoned} on graceful shutdown.
 */
@Component
public class RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunRecorder.class);

    private final JdbcTemplate jdbc;
    private final ProjectDirectory projects;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public RunRecorder(JdbcTemplate jdbc, ProjectDirectory projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    /** Inserts the in-flight row (outcome {@code running}); returns its id, or {@code null} when
     * recording failed (best-effort: never fails the activity being recorded). */
    public UUID start(WorkItemRef item, String agent, String phase, String workflowRunId, String traceUrl) {
        try {
            UUID workItemId = findOrCreateWorkItem(item);
            supersedeRunning(workItemId, agent, phase);
            UUID runId = jdbc.queryForObject("""
                    INSERT INTO runs (work_item_id, agent, phase, workflow_run_id, trace_url, outcome)
                    VALUES (?, ?, ?, ?, ?, 'running')
                    RETURNING id
                    """, UUID.class, workItemId, agent, phase, workflowRunId, traceUrl);
            inFlight.add(runId);
            return runId;
        } catch (RuntimeException e) {
            log.warn("[runs] could not start {} run for {}: {}", agent, item, e.toString());
            return null;
        }
    }

    /** Same as {@link #start} but for callers that already hold the control-plane {@code
     * work_items.id} (no board_id lookup/placeholder-creation needed) — used by the mention flow,
     * whose {@link ai.pdlc.core.domain.AgentMentionRequest} carries the internal work item id, not
     * a board-native id. */
    public UUID startById(UUID workItemId, String agent, String phase, String workflowRunId, String traceUrl) {
        try {
            supersedeRunning(workItemId, agent, phase);
            UUID runId = jdbc.queryForObject("""
                    INSERT INTO runs (work_item_id, agent, phase, workflow_run_id, trace_url, outcome)
                    VALUES (?, ?, ?, ?, ?, 'running')
                    RETURNING id
                    """, UUID.class, workItemId, agent, phase, workflowRunId, traceUrl);
            inFlight.add(runId);
            return runId;
        } catch (RuntimeException e) {
            log.warn("[runs] could not start {} run for work item {}: {}", agent, workItemId, e.toString());
            return null;
        }
    }

    /** Closes the row opened by {@link #start}/{@link #startById}; no-op when {@code runId} is null. */
    public void finish(UUID runId, String outcome) {
        if (runId == null) {
            return;
        }
        try {
            jdbc.update("UPDATE runs SET outcome = ?, finished_at = now() WHERE id = ?", outcome, runId);
        } catch (RuntimeException e) {
            log.warn("[runs] could not finish run {}: {}", runId, e.toString());
        } finally {
            inFlight.remove(runId);
        }
    }

    /** Marks every row this process started but never finished as {@code abandoned}, run on
     * graceful shutdown (Spring destroys beans before {@link
     * ai.pdlc.agents.config.WorkerConfig#shutdown()} stops polling, but {@link #finish} only
     * needs {@link JdbcTemplate}, which outlives this bean either way). A row orphaned by a hard
     * kill (no graceful shutdown) instead relies on {@link #start}/{@link #startById}'s
     * supersede-on-retry, or the control-plane TTL as last resort. */
    @PreDestroy
    public void abandonInFlight() {
        for (UUID id : List.copyOf(inFlight)) {
            finish(id, "abandoned");
        }
    }

    /** Closes any still-{@code running} row for this exact (item, agent, phase) before a new
     * attempt starts — a Temporal retry of the same step always inserts a fresh row, so a prior
     * {@code running} row for that key is by definition an orphan of a dead attempt. Best-effort:
     * failure here never blocks starting the new row. */
    private void supersedeRunning(UUID workItemId, String agent, String phase) {
        try {
            jdbc.update("""
                    UPDATE runs SET outcome = 'abandoned', finished_at = now()
                    WHERE work_item_id = ? AND agent = ? AND phase = ? AND outcome = 'running'
                    """, workItemId, agent, phase);
        } catch (RuntimeException e) {
            log.warn("[runs] could not supersede prior {} run for work item {}: {}", agent, workItemId, e.toString());
        }
    }

    private UUID findOrCreateWorkItem(WorkItemRef item) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM work_items WHERE profile = ? AND board_id = ?",
                (rs, i) -> rs.getObject("id", UUID.class),
                item.profile(), item.boardId());
        if (!ids.isEmpty()) {
            return ids.getFirst();
        }
        String provider = projects.project(item.profile()).board().provider();
        jdbc.update("""
                INSERT INTO work_items (profile, board_provider, board_id, kind, canonical_state)
                VALUES (?, ?, ?, 'feature', 'needs-clarification')
                ON CONFLICT (profile, board_id) DO NOTHING
                """, item.profile(), provider, item.boardId());
        return jdbc.queryForObject(
                "SELECT id FROM work_items WHERE profile = ? AND board_id = ?",
                UUID.class, item.profile(), item.boardId());
    }
}
