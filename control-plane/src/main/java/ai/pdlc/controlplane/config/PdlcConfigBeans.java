package ai.pdlc.controlplane.config;

import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.config.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class PdlcConfigBeans {

    @Bean
    public PdlcConfig pdlcConfig(@Value("${pdlc.config-path}") String configPath) {
        return PdlcConfig.loadFromFile(Path.of(configPath));
    }

    @Bean
    public Profile activeProfile(PdlcConfig pdlcConfig, @Value("${pdlc.active-profile}") String activeProfile) {
        return pdlcConfig.profile(activeProfile);
    }
}
