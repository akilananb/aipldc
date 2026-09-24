package ai.pdlc.agents.activities;

import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.domain.WorkItemRef;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

/** Plain unit test (AGENTS.md pattern 3): no Spring context, no Docker. Covers the
 * stuck-agent-run-banner plan's steps 2-3: superseding a still-{@code running} row for the same
 * (item, agent, phase) before starting a new one, and abandoning every in-flight row on
 * {@link RunRecorder#abandonInFlight()}. */
class RunRecorderTest {

    private static final UUID WORK_ITEM_ID = UUID.randomUUID();

    @Test
    void startSupersedesAnyPriorRunningRowForTheSameKeyBeforeInserting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectDirectory projects = mock(ProjectDirectory.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any()))
                .thenReturn(List.of(WORK_ITEM_ID));
        UUID runId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(), any(), any(), any(), any()))
                .thenReturn(runId);

        RunRecorder recorder = new RunRecorder(jdbc, projects);
        UUID result = recorder.start(new WorkItemRef("local", "4412"), "po", "revise", "wf-1", null);

        assertThat(result).isEqualTo(runId);
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("outcome = 'abandoned'"),
                eq(WORK_ITEM_ID), eq("po"), eq("revise"));
        order.verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.contains("VALUES"),
                eq(UUID.class), eq(WORK_ITEM_ID), eq("po"), eq("revise"), eq("wf-1"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void startByIdSupersedesAnyPriorRunningRowForTheSameKeyBeforeInserting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectDirectory projects = mock(ProjectDirectory.class);
        UUID runId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(), any(), any(), any(), any()))
                .thenReturn(runId);

        RunRecorder recorder = new RunRecorder(jdbc, projects);
        UUID result = recorder.startById(WORK_ITEM_ID, "mention", "analyst", "wf-1", null);

        assertThat(result).isEqualTo(runId);
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("outcome = 'abandoned'"),
                eq(WORK_ITEM_ID), eq("mention"), eq("analyst"));
        order.verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.contains("VALUES"),
                eq(UUID.class), eq(WORK_ITEM_ID), eq("mention"), eq("analyst"), eq("wf-1"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void abandonInFlightFinishesEveryStartedButUnfinishedRunAndOnlyThose() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectDirectory projects = mock(ProjectDirectory.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(), any(), any(), any(), any()))
                .thenReturn(first, second);

        RunRecorder recorder = new RunRecorder(jdbc, projects);
        UUID runIdOne = recorder.startById(WORK_ITEM_ID, "grill", "questions", "wf-1", null);
        UUID runIdTwo = recorder.startById(WORK_ITEM_ID, "grill", "round", "wf-2", null);
        recorder.finish(runIdOne, "ok");

        recorder.abandonInFlight();

        ArgumentCaptor<Object[]> finishArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(eq("UPDATE runs SET outcome = ?, finished_at = now() WHERE id = ?"), finishArgs.capture());
        List<Object[]> calls = finishArgs.getAllValues();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).containsExactly("ok", runIdOne);
        assertThat(calls.get(1)).containsExactly("abandoned", runIdTwo);

        // A second call must be a no-op: the in-flight set was cleared by the first pass.
        recorder.abandonInFlight();
        verify(jdbc, times(2)).update(eq("UPDATE runs SET outcome = ?, finished_at = now() WHERE id = ?"), any(Object[].class));
    }
}
