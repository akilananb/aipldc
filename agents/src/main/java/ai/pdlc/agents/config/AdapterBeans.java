package ai.pdlc.agents.config;

import ai.pdlc.adapters.ado.AdoBoardAdapter;
import ai.pdlc.adapters.github.GitHubRepoAdapter;
import ai.pdlc.adapters.inmemory.InMemoryRepoAdapter;
import ai.pdlc.adapters.localgit.LocalGitRepoAdapter;
import ai.pdlc.adapters.localmetrics.LocalMetricsAdapter;
import ai.pdlc.adapters.remoteboard.RemoteBoardPort;
import ai.pdlc.adapters.secrets.EnvSecretsPort;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.NotifyPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.port.SecretsPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mirrors {@code ai.pdlc.controlplane.config.AdapterBeans} exactly: the reasoning agents read board
 * and repo context through the same hexagonal ports and the same provider selection (active profile's
 * {@code board.provider}/{@code repo.provider}) as control-plane (tech-stack §2).
 */
@Configuration
public class AdapterBeans {

    @Bean
    public SecretsPort secretsPort() {
        return new EnvSecretsPort();
    }

    @Bean
    public BoardPort boardPort(Profile activeProfile, SecretsPort secretsPort,
                                @Value("${pdlc.control-plane-url:http://control-plane:8081}") String controlPlaneUrl) {
        return switch (activeProfile.board().provider()) {
            case "azure-devops" -> new AdoBoardAdapter(
                    activeProfile.board().org(),
                    activeProfile.board().project(),
                    secretsPort.resolve(activeProfile.board().auth().secretRef()),
                    activeProfile.board().states(),
                    activeProfile.board().types());
            // The in-memory provider's board state lives only in control-plane's own JVM
            // (orchestration-decision §6: agents is a separate process); reading through its REST
            // API is how the grill/PO agents actually see real title/description/comments instead
            // of an empty local map. See RemoteBoardPort's javadoc for the bug this fixes.
            default -> new RemoteBoardPort(controlPlaneUrl);
        };
    }

    @Bean
    public RepoPort repoPort(Profile activeProfile, SecretsPort secretsPort) {
        return switch (activeProfile.repo().provider()) {
            case "github" -> new GitHubRepoAdapter(activeProfile.repo().url(), secretsPort.resolve("kv://github-pat"));
            case "local-git" -> new LocalGitRepoAdapter(activeProfile.repo().url());
            default -> new InMemoryRepoAdapter();
        };
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
                // Pilot: no Slack/Teams/email integration.
            }
        };
    }
}
