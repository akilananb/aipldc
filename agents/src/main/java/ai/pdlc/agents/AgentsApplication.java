package ai.pdlc.agents;

import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.config.Profile;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Embabel agent service + Temporal worker (task queue {@code reasoning}).
 *
 * <p>Before the Spring context starts, the active pdlc.yaml profile's {@code agents.gateway} and
 * {@code agents.roles.<role>.model} are translated into the Embabel custom OpenAI-compatible provider
 * properties as system properties (Spring's Environment ranks system properties above {@code
 * application.yml} and OS env vars), so the per-role model names are registered with the gateway for
 * {@code Ai.withLlm(LlmOptions.withModel(roleModel))} to resolve at runtime. The base URL is used
 * verbatim (no implicit path suffix) and can be overridden without touching pdlc.yaml via {@code
 * PDLC_LLM_BASE_URL}; the API key comes from {@code OPENAI_CUSTOM_API_KEY} or {@code
 * PDLC_LLM_API_KEY} and may be blank for endpoints that don't check authentication. A second hook,
 * {@link #applyLangfuseFromEnv()}, turns {@code LANGFUSE_PUBLIC_KEY}/{@code LANGFUSE_SECRET_KEY}
 * into the OTLP Basic-auth header and tracing-enabled flag consumed by {@code application.yml}.
 */
@SpringBootApplication
public class AgentsApplication {

    private static final String CUSTOM_PREFIX = "embabel.agent.platform.models.openai.custom";

    public static void main(String[] args) {
        applyLlmRoutingFromConfig(System::getenv);
        applyLangfuseFromEnv();
        SpringApplication.run(AgentsApplication.class, args);
    }

    /** Best-effort: derives gateway + role models from pdlc.yaml; falls back to env-var defaults.
     * {@code env} is injected so routing precedence can be unit-tested without mutating the real
     * process environment. */
    static void applyLlmRoutingFromConfig(Function<String, String> env) {
        String configPath = envOr(env, "PDLC_CONFIG_PATH", "infra/pdlc.yaml");
        String activeProfile = envOr(env, "PDLC_ACTIVE_PROFILE", "local");
        String baseUrl = envOr(env, "PDLC_LLM_BASE_URL", null);
        String apiKey = envOr(env, "OPENAI_CUSTOM_API_KEY", null);
        if (apiKey == null) {
            apiKey = envOr(env, "PDLC_LLM_API_KEY", null);
        }
        try {
            Path path = Path.of(configPath);
            if (Files.isReadable(path)) {
                PdlcConfig config = PdlcConfig.loadFromFile(path);
                Profile profile = config.profile(activeProfile);
                if (baseUrl == null) {
                    String gateway = profile.agents().gateway();
                    if (gateway != null && !gateway.isBlank()) {
                        baseUrl = gateway;
                    }
                }
                Set<String> models = new LinkedHashSet<>();
                profile.agents().roles().values().stream()
                        .map(role -> role.model())
                        .filter(m -> m != null && !m.isBlank())
                        .forEach(models::add);
                if (!models.isEmpty()) {
                    System.setProperty(CUSTOM_PREFIX + ".models", String.join(",", models));
                    // Embabel's own default ("gpt-4.1-mini") isn't one of our registered custom
                    // models; pin the platform default to whichever role model loads first so
                    // `ai.withDefaultLlm()` callers (if any) still resolve.
                    System.setProperty("embabel.models.default-llm", models.iterator().next());
                }
            }
        } catch (RuntimeException e) {
            // Non-fatal: application.yml env-var placeholders still provide defaults; the agent will
            // fail loudly on first LLM use if the provider is genuinely misconfigured.
            System.err.println("[agents] could not derive LLM routing from " + configPath + ": " + e.getMessage());
        }
        boolean apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        // blank/absent key = unauthenticated endpoint; the openai-java SDK requires a non-empty
        // Bearer token, so a placeholder is sent and ignored by servers that don't check it.
        System.setProperty(CUSTOM_PREFIX + ".api-key", apiKeyConfigured ? apiKey : "stub");
        if (baseUrl != null && !baseUrl.isBlank()) {
            // Any OpenAI-compatible base URL, used verbatim (PDLC_LLM_BASE_URL overrides
            // agents.gateway from pdlc.yaml); the caller is responsible for including /v1 (or
            // whatever path segment) the target server expects.
            String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            System.setProperty(CUSTOM_PREFIX + ".base-url", normalized);
            System.out.println("[agents] LLM endpoint " + normalized + " (auth: " + (apiKeyConfigured ? "api-key" : "none") + ")");
        }
    }

    /** Turns LANGFUSE_PUBLIC_KEY/LANGFUSE_SECRET_KEY into the Basic-auth header application.yml
     * expects and switches tracing export on; both absent = tracing stays off silently, exactly one
     * absent = off with a warning. System properties, so they outrank the yml placeholders. */
    static void applyLangfuseFromEnv() {
        String publicKey = envOr("LANGFUSE_PUBLIC_KEY", null);
        String secretKey = envOr("LANGFUSE_SECRET_KEY", null);
        if (publicKey == null && secretKey == null) {
            return;
        }
        if (publicKey == null || secretKey == null) {
            System.err.println("[agents] LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY must both be set; tracing stays off");
            return;
        }
        String basic = Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        System.setProperty("LANGFUSE_OTLP_BASIC_AUTH", basic);
        System.setProperty("LANGFUSE_TRACING_ENABLED", "true");
        System.out.println("[agents] Langfuse tracing enabled -> " + envOr("LANGFUSE_OTLP_ENDPOINT", "http://localhost:3000/api/public/otel/v1/traces"));
    }

    private static String envOr(String name, String fallback) {
        return envOr(System::getenv, name, fallback);
    }

    private static String envOr(Function<String, String> env, String name, String fallback) {
        String value = env.apply(name);
        return value != null && !value.isBlank() ? value : fallback;
    }
}
