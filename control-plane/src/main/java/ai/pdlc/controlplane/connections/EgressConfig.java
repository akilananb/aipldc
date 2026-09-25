package ai.pdlc.controlplane.connections;

import ai.pdlc.core.platform.EgressPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The enterprise egress policy for tool destinations (docs/phase-2-execution-spec.md slice 2.1).
 * {@code pdlc.egress.allowed-private-hosts} lists internal hosts tools may reach despite resolving
 * to non-public addresses; everything else must be publicly routable.
 */
@Configuration
public class EgressConfig {

    @Bean
    EgressPolicy egressPolicy(@Value("${pdlc.egress.allowed-private-hosts:}") String allowedPrivateHosts) {
        Set<String> hosts = Arrays.stream(allowedPrivateHosts.split(","))
                .map(String::trim).filter(h -> !h.isEmpty()).collect(Collectors.toSet());
        return new EgressPolicy(hosts);
    }
}
