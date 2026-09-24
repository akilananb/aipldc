package ai.pdlc.agents.config;

import ai.pdlc.adapters.localmetrics.LocalMetricsAdapter;
import ai.pdlc.adapters.secrets.EnvSecretsPort;
import ai.pdlc.core.port.NotifyPort;
import ai.pdlc.core.port.SecretsPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deployment-only ports with no per-project variant. Per-project {@code BoardPort}/{@code
 * RepoPort} resolution lives in {@link PortRegistry} now that board/repo config is DB-backed and
 * Admin-editable at runtime (tech-stack §2).
 */
@Configuration
public class AdapterBeans {

    @Bean
    public SecretsPort secretsPort() {
        return new EnvSecretsPort();
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
