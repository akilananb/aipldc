package ai.pdlc.controlplane.identity;

import ai.pdlc.controlplane.web.ApiExceptionHandler;
import ai.pdlc.controlplane.web.MeController;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Minimal web context for the security-chain tests: the real {@link SecurityConfig},
 * {@link IdentityResolver}, {@link MeController} and {@link ApiExceptionHandler}, plus stub
 * endpoints on the same paths as the real controllers. No database, Temporal or adapters.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration"})
@Import({SecurityConfig.class, OidcIdentityMapper.class, IdentityResolver.class, MeController.class,
        ApiExceptionHandler.class, SecurityTestApp.StubEndpoints.class})
class SecurityTestApp {

    @RestController
    static class StubEndpoints {

        private final IdentityResolver identityResolver;

        StubEndpoints(IdentityResolver identityResolver) {
            this.identityResolver = identityResolver;
        }

        /** An open read in dev mode, a user-only read otherwise. */
        @GetMapping("/api/items")
        Map<String, String> items() {
            return Map.of("ok", "items");
        }

        /** A mutation that resolves the caller, like every real one. */
        @PostMapping("/api/workspaces")
        Map<String, String> createWorkspace(HttpServletRequest request) {
            Identity identity = identityResolver.resolve(request);
            return Map.of("user", identity.user(), "role", identity.role());
        }

        @PostMapping("/api/build-tasks/claim")
        Map<String, String> claim() {
            return Map.of("ok", "claimed");
        }

        @GetMapping("/api/board/local/1")
        Map<String, String> board() {
            return Map.of("ok", "board");
        }

        @PostMapping("/webhooks/local")
        Map<String, String> webhook() {
            return Map.of("ok", "webhook");
        }
    }
}
