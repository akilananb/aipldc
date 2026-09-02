package ai.pdlc.controlplane.config;

import ai.pdlc.adapters.ado.AdoBoardAdapter;
import ai.pdlc.adapters.github.GitHubRepoAdapter;
import ai.pdlc.adapters.inmemory.InMemoryBoardAdapter;
import ai.pdlc.adapters.inmemory.InMemoryRepoAdapter;
import ai.pdlc.adapters.localci.LocalCiAdapter;
import ai.pdlc.adapters.localgit.LocalGitRepoAdapter;
import ai.pdlc.adapters.localmetrics.LocalMetricsAdapter;
import ai.pdlc.adapters.secrets.EnvSecretsPort;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.CiPort;
import ai.pdlc.core.port.MetricsPort;
import ai.pdlc.core.port.NotifyPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.port.SecretsPort;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
/**
 * Hexagonal seam: which adapter backs each port is a config change (the active profile's
 * {@code board.provider}/{@code repo.provider}), never an agent/workflow-code change (tech-stack §2
 * "Principles").
 */
@Configuration
public class AdapterBeans {

    private static final Logger log = LoggerFactory.getLogger(AdapterBeans.class);

    @Bean
    public SecretsPort secretsPort() {
        return new EnvSecretsPort();
    }

    @Bean
    public BoardPort boardPort(Profile activeProfile, SecretsPort secretsPort, JdbcTemplate jdbcTemplate) {
        return switch (activeProfile.board().provider()) {
            case "azure-devops" -> new AdoBoardAdapter(
                    activeProfile.board().org(),
                    activeProfile.board().project(),
                    secretsPort.resolve(activeProfile.board().auth().secretRef()),
                    activeProfile.board().states(),
                    activeProfile.board().types());
            default -> new InMemoryBoardAdapter(nextBoardIdSequenceStart(jdbcTemplate));
        };
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
    public RepoPort repoPort(Profile activeProfile, SecretsPort secretsPort) {
        // pdlc.yaml's consumed repo.* subset (tech-stack §4) has no `auth` key (unlike board.auth);
        // the pilot resolves GitHub's PAT by convention: env GITHUB_PAT via kv://github-pat.
        return switch (activeProfile.repo().provider()) {
            case "github" -> new GitHubRepoAdapter(activeProfile.repo().url(), secretsPort.resolve("kv://github-pat"));
            case "local-git" -> new LocalGitRepoAdapter(activeProfile.repo().url());
            default -> new InMemoryRepoAdapter();
        };
    }

    /** Single {@code local-ci} provider (build-order step 6 defers a second CI adapter); needs a
     * real local git checkout, so only exercised end-to-end for the {@code local-git} repo
     * provider - matches the {@code ado-pilot} profile's other untested-without-a-PAT surfaces. */
    @Bean
    public CiPort ciPort(Profile activeProfile) {
        return new LocalCiAdapter(activeProfile.repo().url());
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
