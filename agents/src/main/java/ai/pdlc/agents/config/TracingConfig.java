package ai.pdlc.agents.config;

import ai.pdlc.agents.tracing.LangfuseObservationFilter;
import io.micrometer.observation.ObservationFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfig {

    /** Only when traces are exported: with tracing off there is nothing to attach prompts to. */
    @Bean
    @ConditionalOnProperty(name = "management.tracing.export.enabled", havingValue = "true")
    public ObservationFilter langfuseObservationFilter() {
        return new LangfuseObservationFilter();
    }
}
