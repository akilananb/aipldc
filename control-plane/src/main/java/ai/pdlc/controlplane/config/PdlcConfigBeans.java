package ai.pdlc.controlplane.config;

import ai.pdlc.adapters.projects.JdbcProjectDirectory;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;

@Configuration
public class PdlcConfigBeans {

    @Bean
    public PdlcConfig pdlcConfig(@Value("${pdlc.config-path}") String configPath) {
        return PdlcConfig.loadFromFile(Path.of(configPath));
    }

    /** Deployment-level settings only (`agents.gateway/roles/prompts_dir`, `notify`) resolved from
     * {@code pdlc.yaml}'s {@code PDLC_ACTIVE_PROFILE} entry — board/repos/gates for an actual
     * project now come from {@link ProjectDirectory#project}, never from this bean. The sole
     * {@link Profile} bean in this context, so every existing {@code Profile activeProfile}
     * constructor parameter across control-plane still autowires unambiguously by type. */
    @Bean
    public Profile deploymentProfile(PdlcConfig pdlcConfig, @Value("${pdlc.active-profile}") String activeProfile) {
        return pdlcConfig.profile(activeProfile);
    }

    @Bean
    public ProjectDirectory projectDirectory(DataSource dataSource, PdlcConfig pdlcConfig,
                                              @Value("${pdlc.active-profile}") String activeProfile) {
        // Own Jackson 2 mapper rather than an injected Spring bean: Spring Boot 4's Jackson
        // auto-configuration wires a `tools.jackson` (Jackson 3) ObjectMapper for HTTP message
        // conversion, not `com.fasterxml.jackson.databind.ObjectMapper` (see BuildTasksController).
        return new JdbcProjectDirectory(dataSource, pdlcConfig, activeProfile, new ObjectMapper());
    }
}
