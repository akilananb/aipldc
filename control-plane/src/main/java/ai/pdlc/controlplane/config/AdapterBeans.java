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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * Hexagonal seam: which adapter backs each port is a config change (the active profile's
 * {@code board.provider}/{@code repo.provider}), never an agent/workflow-code change (tech-stack §2
 * "Principles").
 */
@Configuration
public class AdapterBeans {

    @Bean
    public SecretsPort secretsPort() {
        return new EnvSecretsPort();
    }

    @Bean
    public BoardPort boardPort(Profile activeProfile, SecretsPort secretsPort) {
        return switch (activeProfile.board().provider()) {
            case "azure-devops" -> new AdoBoardAdapter(
                    activeProfile.board().org(),
                    activeProfile.board().project(),
                    secretsPort.resolve(activeProfile.board().auth().secretRef()),
                    activeProfile.board().states(),
                    activeProfile.board().types());
            default -> new InMemoryBoardAdapter();
        };
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
