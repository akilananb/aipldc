package ai.pdlc.controlplane.config;

import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.config.Profile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Loads the real deployment {@code infra/pdlc.yaml} (not a fixture copy) to catch drift between
 * that file and what {@code PdlcConfig}/{@code AdapterBeans}/the webhook ingress actually expect. */
class InfraPdlcYamlTest {

    private static final Path INFRA_PDLC_YAML = Path.of("../infra/pdlc.yaml");

    @Test
    void localProfileLoadsWithAllThreeGatesAndLocalGitRepo() {
        PdlcConfig config = PdlcConfig.loadFromFile(INFRA_PDLC_YAML);
        Profile local = config.profile("local");

        assertThat(local.board().provider()).isEqualTo("in-memory");
        assertThat(local.repo().provider()).isEqualTo("local-git");
        assertThat(local.repo().defaultBranch()).isEqualTo("main");
        assertThat(local.gate("G1").roles()).containsExactly("PO", "SquadLead");
        assertThat(local.gate("G1").sod()).isTrue();
        assertThat(local.gate("G2").roles()).containsExactly("FSDeveloper", "QA");
        assertThat(local.gate("G2").sod()).isTrue();
        assertThat(local.gate("G3").roles()).containsExactly("PO", "SquadLead", "QA");
        assertThat(local.gate("G3").sod()).isTrue();
        assertThat(local.agents().roles()).containsKeys("grill", "po", "review", "release", "quality");
    }

    @Test
    void adoPilotProfileLoadsWithAdoAndGithubProviders() {
        PdlcConfig config = PdlcConfig.loadFromFile(INFRA_PDLC_YAML);
        Profile adoPilot = config.profile("ado-pilot");

        assertThat(adoPilot.board().provider()).isEqualTo("azure-devops");
        assertThat(adoPilot.repo().provider()).isEqualTo("github");
        assertThat(adoPilot.board().states()).containsEntry("awaiting-G1", "Awaiting Approval");
        assertThat(adoPilot.gate("G1").roles()).containsExactly("PO", "SquadLead");
    }
}
