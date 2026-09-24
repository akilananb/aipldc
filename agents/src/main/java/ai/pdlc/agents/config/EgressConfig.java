package ai.pdlc.agents.config;

import ai.pdlc.core.platform.EgressPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.stream.Collectors;

/** The egress policy the ToolExecutor enforces on every call (docs/phase-2-execution-spec.md slice 2.1). */
@Configuration
public class EgressConfig {

    @Bean
    EgressPolicy egressPolicy(@Value("${pdlc.egress.allowed-private-hosts:}") String allowedPrivateHosts) {
        return new EgressPolicy(Arrays.stream(allowedPrivateHosts.split(","))
                .map(String::trim).filter(h -> !h.isEmpty()).collect(Collectors.toSet()));
    }
}
