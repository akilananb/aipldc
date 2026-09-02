package ai.pdlc.agents.activities;

import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.WorkItemRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Appends one row per agent run to the shared {@code runs} table via plain {@link JdbcTemplate}
 * (plan step 6: spring-boot-starter-jdbc only, no spring-data-jdbc). Best-effort: a recording failure
 * never fails the activity it is recording. Also back-fills a minimal {@code work_items} placeholder
 * row so the {@code runs.work_item_id} FK never trips before control-plane has inserted the feature
 * (e.g. the very first {@code grillEvaluate}).
 */
@Component
public class RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunRecorder.class);

    private final JdbcTemplate jdbc;
    private final Profile activeProfile;

    public RunRecorder(JdbcTemplate jdbc, Profile activeProfile) {
        this.jdbc = jdbc;
        this.activeProfile = activeProfile;
    }

    public void record(WorkItemRef item, String agent, String workflowRunId, String outcome,
                       Long tokens, Integer iterations) {
        try {
            UUID workItemId = findOrCreateWorkItem(item);
            jdbc.update("""
                    INSERT INTO runs (work_item_id, agent, workflow_run_id, trace_url, tokens, iterations, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, workItemId, agent, workflowRunId, null, tokens, iterations, outcome);
        } catch (RuntimeException e) {
            log.warn("[runs] could not record {} run for {}: {}", agent, item, e.toString());
        }
    }

    /** Same as {@link #record} but for callers that already hold the control-plane {@code
     * work_items.id} (no board_id lookup/placeholder-creation needed) — used by the mention flow,
     * whose {@link ai.pdlc.core.domain.AgentMentionRequest} carries the internal work item id, not
     * a board-native id. */
    public void recordById(UUID workItemId, String agent, String workflowRunId, String outcome,
                            Long tokens, Integer iterations) {
        try {
            jdbc.update("""
                    INSERT INTO runs (work_item_id, agent, workflow_run_id, trace_url, tokens, iterations, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, workItemId, agent, workflowRunId, null, tokens, iterations, outcome);
        } catch (RuntimeException e) {
            log.warn("[runs] could not record {} run for work item {}: {}", agent, workItemId, e.toString());
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
        String provider = activeProfile.board().provider();
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
