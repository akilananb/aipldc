package ai.pdlc.controlplane.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.assertj.core.api.Assertions.assertThatCode;

class WebCorsConfigTest {

    @Test
    void registersCorsMappingForAllPathsWithoutThrowing() {
        WebCorsConfig config = new WebCorsConfig();
        CorsRegistry registry = new CorsRegistry();
        assertThatCode(() -> config.addCorsMappings(registry)).doesNotThrowAnyException();
    }
}
