package ai.pdlc.controlplane.config;

import ai.pdlc.adapters.inmemory.InMemoryBoardAdapter;
import ai.pdlc.adapters.localmetrics.LocalMetricsAdapter;
import ai.pdlc.adapters.secrets.EnvSecretsPort;
import ai.pdlc.core.port.MetricsPort;
import ai.pdlc.core.port.NotifyPort;
import ai.pdlc.core.port.SecretsPort;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
/**
 * Deployment-only ports with no per-project variant. Per-project {@code BoardPort}/{@code
 * RepoPort}/{@code CiPort} resolution lives in {@link PortRegistry} now that board/repo config is
 * DB-backed and Admin-editable at runtime (tech-stack §2 "Principles": which adapter backs a port
 * is a config change, never an agent/workflow-code change).
 */
@Configuration
public class AdapterBeans {

    private static final Logger log = LoggerFactory.getLogger(AdapterBeans.class);

    @Bean
    public SecretsPort secretsPort() {
        return new EnvSecretsPort();
    }


    /** {@link InMemoryBoardAdapter}'s own id sequence starts fresh every process restart, with no
     * memory of ids a prior process already persisted to {@code work_items}. Without this, a
     * restart can reissue an already-used board id; {@code ensureWorkItem}'s (profile, board_id)
     * lookup then finds that id "already exists" and silently rebinds a brand-new work item (and
     * every DB row it writes next, e.g. its artifact) to an unrelated pre-existing story. Seed one
     * past whatever numeric board id is already on record, defaulting to the adapter's own fixture
     * floor (4412) on a fresh database - Spring doesn't guarantee Flyway has migrated {@code
     * work_items} into existence before this {@code @Bean} method runs, so a query failure (e.g.
     * "relation does not exist" on a brand-new database) falls back to the same floor rather than
     * crashing startup. */
    static long nextBoardIdSequenceStart(JdbcTemplate jdbcTemplate) {
        try {
            Long max = jdbcTemplate.queryForObject(
                    "SELECT MAX(board_id::bigint) FROM work_items WHERE board_id ~ '^[0-9]+$'", Long.class);
            return max == null ? 4412 : Math.max(max + 1, 4412);
        } catch (DataAccessException e) {
            log.warn("[boardPort] could not read work_items.board_id at startup (fresh database?); "
                    + "starting the in-memory id sequence at the fixture floor 4412", e);
            return 4412;
        }
    }


    @Bean
    public LocalMetricsAdapter metricsPort(javax.sql.DataSource dataSource) {
        return new LocalMetricsAdapter(dataSource);
    }

    @Bean
    public NotifyPort notifyPort() {
        return new NotifyPort() {
            @Override
            public String requestApproval(String to, java.util.Map<String, Object> payload) {
                return "noop";
            }

            @Override
            public void post(String channel, String message) {
                // Pilot: no Slack/Teams/email integration (tech-stack §1 NotifyPort alternatives).
            }
        };
    }
}
