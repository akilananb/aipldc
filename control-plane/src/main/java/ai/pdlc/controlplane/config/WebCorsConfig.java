package ai.pdlc.controlplane.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The review UI (Vite dev server or the nginx-served build, both on their own origin) calls this
 * REST API cross-origin and sends the dev-header auth shim ({@code X-User}/{@code X-Role}), so the
 * API must explicitly allow those headers - the browser enforces CORS even though {@code curl} does
 * not, which is why this was missed until browser verification.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
