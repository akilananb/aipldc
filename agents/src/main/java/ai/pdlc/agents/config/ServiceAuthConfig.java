package ai.pdlc.agents.config;

import ai.pdlc.adapters.serviceauth.ClientCredentialsTokenSource;
import ai.pdlc.adapters.serviceauth.ServiceCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The agents process's service identity toward control-plane (docs/phase-1-execution-spec.md
 * slice 2): a client-credentials JWT ({@code pdlc.service-auth.token-url} + client id/secret,
 * scope {@code pdlc.board.read}) in production, a static {@code X-Service-Token} on a dev stack,
 * or none when control-plane leaves {@code /api/board/**} open (dev headers, no service token).
 */
@Configuration
public class ServiceAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(ServiceAuthConfig.class);

    @Bean
    public ServiceCredentials serviceCredentials(@Value("${pdlc.service-auth.token-url:}") String tokenUrl,
                                                 @Value("${pdlc.service-auth.client-id:}") String clientId,
                                                 @Value("${pdlc.service-auth.client-secret:}") String clientSecret,
                                                 @Value("${pdlc.service-auth.scope:pdlc.board.read}") String scope,
                                                 @Value("${pdlc.service-auth.static-token:}") String staticToken) {
        if (!tokenUrl.isBlank()) {
            if (clientId.isBlank() || clientSecret.isBlank()) {
                throw new IllegalStateException("pdlc.service-auth.token-url is set but client-id/client-secret are missing");
            }
            log.info("Authenticating to control-plane with client-credentials tokens from {}", tokenUrl);
            return ServiceCredentials.bearer(new ClientCredentialsTokenSource(tokenUrl, clientId, clientSecret, scope));
        }
        if (!staticToken.isBlank()) {
            log.info("Authenticating to control-plane with a static X-Service-Token (dev only)");
            return ServiceCredentials.staticToken(staticToken);
        }
        return ServiceCredentials.NONE;
    }
}
