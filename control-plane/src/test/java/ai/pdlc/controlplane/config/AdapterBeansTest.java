package ai.pdlc.controlplane.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link AdapterBeans#nextBoardIdSequenceStart} - the fix for a live bug where the in-memory
 * board's id sequence reset to a fixed floor on every process restart, reissuing an id already
 * persisted in Postgres and silently rebinding a brand-new work item to an unrelated old row. */
class AdapterBeansTest {

    @Test
    void startsAbovePersistedMaxBoardId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Long.class))).thenReturn(9002L);

        assertThat(AdapterBeans.nextBoardIdSequenceStart(jdbc)).isEqualTo(9003L);
    }

    @Test
    void fallsBackToTheFixtureFloorOnAnEmptyDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Long.class))).thenReturn(null);

        assertThat(AdapterBeans.nextBoardIdSequenceStart(jdbc)).isEqualTo(4412L);
    }

    @Test
    void fallsBackToTheFixtureFloorInsteadOfCrashingWhenWorkItemsDoesNotExistYet() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("relation \"work_items\" does not exist"));

        assertThat(AdapterBeans.nextBoardIdSequenceStart(jdbc)).isEqualTo(4412L);
    }
}
