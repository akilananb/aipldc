package ai.pdlc.agents;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentsApplication#applyLlmRoutingFromConfig} — the sole place base-url/api-key
 * precedence between {@code pdlc.yaml}'s {@code agents.gateway} and the {@code PDLC_LLM_*} env
 * overrides is decided. The env lookup is injected (rather than mutating the real process
 * environment) so these cases run in isolation and in parallel with everything else.
 */
class AgentsApplicationRoutingTest {

    private static final String BASE_URL_PROP = "embabel.agent.platform.models.openai.custom.base-url";
    private static final String API_KEY_PROP = "embabel.agent.platform.models.openai.custom.api-key";
    private static final String MODELS_PROP = "embabel.agent.platform.models.openai.custom.models";
    private static final String DEFAULT_LLM_PROP = "embabel.models.default-llm";

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(BASE_URL_PROP);
        System.clearProperty(API_KEY_PROP);
        System.clearProperty(MODELS_PROP);
        System.clearProperty(DEFAULT_LLM_PROP);
    }

    private static Path writeFixture(Path dir) throws IOException {
        Path file = dir.resolve("pdlc.yaml");
        Files.writeString(file, """
                profiles:
                  t:
                    agents:
                      gateway: http://gw:4000/custom/
                      roles:
                        grill: { model: m-one }
                    gates:
                      G1: { roles: [PO] }
                """);
        return file;
    }

    @Test
    void gatewayAloneIsUsedVerbatimWithNoImplicitV1Suffix(@TempDir Path dir) throws IOException {
        Path configFile = writeFixture(dir);
        Map<String, String> env = Map.of(
                "PDLC_CONFIG_PATH", configFile.toString(),
                "PDLC_ACTIVE_PROFILE", "t");

        AgentsApplication.applyLlmRoutingFromConfig(env::get);

        assertThat(System.getProperty(BASE_URL_PROP)).isEqualTo("http://gw:4000/custom");
        assertThat(System.getProperty(API_KEY_PROP)).isEqualTo("stub");
    }

    @Test
    void envOverridesTakePrecedenceOverGateway(@TempDir Path dir) throws IOException {
        Path configFile = writeFixture(dir);
        Map<String, String> env = Map.of(
                "PDLC_CONFIG_PATH", configFile.toString(),
                "PDLC_ACTIVE_PROFILE", "t",
                "PDLC_LLM_BASE_URL", "http://host.docker.internal:11434/v1",
                "PDLC_LLM_API_KEY", "k1");

        AgentsApplication.applyLlmRoutingFromConfig(env::get);

        assertThat(System.getProperty(BASE_URL_PROP)).isEqualTo("http://host.docker.internal:11434/v1");
        assertThat(System.getProperty(API_KEY_PROP)).isEqualTo("k1");
    }

    @Test
    void baseUrlOverrideAppliesEvenWhenConfigFileIsMissing(@TempDir Path dir) {
        Map<String, String> env = Map.of(
                "PDLC_CONFIG_PATH", dir.resolve("does-not-exist.yaml").toString(),
                "PDLC_ACTIVE_PROFILE", "t",
                "PDLC_LLM_BASE_URL", "http://host.docker.internal:11434/v1");

        AgentsApplication.applyLlmRoutingFromConfig(env::get);

        assertThat(System.getProperty(BASE_URL_PROP)).isEqualTo("http://host.docker.internal:11434/v1");
        assertThat(System.getProperty(API_KEY_PROP)).isEqualTo("stub");
    }
}
