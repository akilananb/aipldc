package ai.pdlc.core.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdlcConfigTest {

    private static final Path FIXTURE = Path.of("src/test/resources/pdlc-example.yaml");

    @Test
    void roundTripsPaymentsSquadProfile() {
        PdlcConfig config = PdlcConfig.loadFromFile(FIXTURE);
        Profile payments = config.profile("payments-squad");

        assertThat(payments.board().provider()).isEqualTo("azure-devops");
        assertThat(payments.board().org()).isEqualTo("https://dev.azure.com/acme");
        assertThat(payments.board().project()).isEqualTo("Payments");
        assertThat(payments.board().types()).containsEntry("feature", "Feature").containsEntry("story", "User Story");
        assertThat(payments.board().states()).containsEntry("awaiting-G1", "Awaiting Approval");
        assertThat(payments.board().auth().secretRef()).isEqualTo("kv://ado-pat");

        assertThat(payments.repo().provider()).isEqualTo("github");
        assertThat(payments.repo().url()).isEqualTo("https://github.com/acme/orders-service");
        assertThat(payments.repo().defaultBranch()).isEqualTo("main");
        assertThat(payments.repo().specDir()).isEqualTo("openspec");

        assertThat(payments.notifyConfig().provider()).isEqualTo("slack");
        assertThat(payments.notifyConfig().channel()).isEqualTo("#payments-releases");

        assertThat(payments.agents().gateway()).isEqualTo("https://litellm.internal");
        assertThat(payments.agents().roles().get("grill").model()).isEqualTo("anthropic/claude-sonnet");
        assertThat(payments.agents().roles().get("grill").budgetTokens()).isEqualTo(60000L);
        assertThat(payments.agents().roles().get("po").budgetTokens()).isEqualTo(40000L);

        assertThat(payments.gate("G1").roles()).containsExactly("PO", "SquadLead");
        assertThat(payments.gate("G1").sod()).isTrue();
        assertThat(payments.gate("G2").roles()).containsExactly("FSDeveloper", "QA");
    }

    @Test
    void roundTripsMobileSquadProfileWithMinimalKeys() {
        PdlcConfig config = PdlcConfig.loadFromFile(FIXTURE);
        Profile mobile = config.profile("mobile-squad");

        assertThat(mobile.board().provider()).isEqualTo("jira");
        assertThat(mobile.board().states()).containsEntry("new", "To Do");
        assertThat(mobile.repo().provider()).isEqualTo("azure-repos");
        assertThat(mobile.gate("G1").roles()).containsExactly("PO", "SquadLead");
    }

    @Test
    void unknownTopLevelKeysAreIgnored() {
        // payments-squad has `ci` and `release_pack` keys the pilot does not model; loading must not fail.
        PdlcConfig config = PdlcConfig.loadFromFile(FIXTURE);
        assertThat(config.profiles()).containsKeys("payments-squad", "mobile-squad");
    }

    @Test
    void missingProfileFailsNamingTheKey() {
        PdlcConfig config = PdlcConfig.loadFromFile(FIXTURE);
        assertThatThrownBy(() -> config.profile("does-not-exist"))
                .isInstanceOf(PdlcConfigException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void missingGate1FailsNamingTheKey() {
        String yaml = """
                profiles:
                  no-gate1:
                    board: { provider: azure-devops, org: https://dev.azure.com/acme, project: X }
                    repo: { provider: github, url: https://github.com/acme/x }
                    gates:
                      G2: { roles: [FSDeveloper, QA], sod: true }
                """;
        PdlcConfig config = PdlcConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> config.profile("no-gate1"))
                .isInstanceOf(PdlcConfigException.class)
                .hasMessageContaining("gates.G1");
    }

    @Test
    void legacyRepoYieldsOnePrimaryRepoWithIdMainAndNoAreas() {
        String yaml = """
                profiles:
                  legacy:
                    board: { provider: azure-devops, org: https://dev.azure.com/acme, project: X }
                    repo: { provider: github, url: https://github.com/acme/x, default_branch: main, spec_dir: openspec }
                    gates:
                      G1: { roles: [PO, SquadLead], sod: true }
                """;
        PdlcConfig config = PdlcConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        Profile legacy = config.profile("legacy");
        assertThat(legacy.repos()).hasSize(1);
        RepoConfig repo = legacy.repo();
        assertThat(repo.id()).isEqualTo("main");
        assertThat(repo.primary()).isTrue();
        assertThat(repo.areas()).isEmpty();
        assertThat(repo.url()).isEqualTo("https://github.com/acme/x");
        assertThat(repo.defaultBranch()).isEqualTo("main");
        assertThat(repo.specDir()).isEqualTo("openspec");
    }

    @Test
    void reposListWithoutPrimaryMarksTheFirstEntryPrimary() {
        String yaml = """
                profiles:
                  multi:
                    board: { provider: azure-devops, org: https://dev.azure.com/acme, project: X }
                    repos:
                      - { id: web, provider: github, url: https://github.com/acme/web, default_branch: main, spec_dir: openspec }
                      - { id: api, provider: github, url: https://github.com/acme/api, default_branch: main, spec_dir: openspec, areas: [orders] }
                    gates:
                      G1: { roles: [PO, SquadLead], sod: true }
                """;
        PdlcConfig config = PdlcConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        Profile multi = config.profile("multi");
        assertThat(multi.repos()).hasSize(2);
        assertThat(multi.repo().id()).isEqualTo("web");
        assertThat(multi.repo().primary()).isTrue();
        assertThat(multi.repo("api").primary()).isFalse();
        assertThat(multi.repo("api").areas()).containsExactly("orders");
    }

    @Test
    void repoAndReposTogetherFail() {
        String yaml = """
                profiles:
                  both:
                    board: { provider: azure-devops, org: https://dev.azure.com/acme, project: X }
                    repo: { provider: github, url: https://github.com/acme/x }
                    repos:
                      - { id: web, provider: github, url: https://github.com/acme/web }
                    gates:
                      G1: { roles: [PO, SquadLead], sod: true }
                """;
        assertThatThrownBy(() -> PdlcConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(PdlcConfigException.class)
                .hasMessageContaining("use either repo or repos");
    }

    @Test
    void projectMetaParsesNameConfluenceDocsBriefAndBuild() {
        String yaml = """
                profiles:
                  meta:
                    project:
                      name: Payments
                      confluence_url: https://acme.atlassian.net/wiki/spaces/PAY
                      docs:
                        - { title: Tech stack, url: https://acme/wiki/tech-stack }
                      brief: Kitchen ticket fidelity pilot
                      build: { acp_agent: my acp agent }
                    board: { provider: azure-devops, org: https://dev.azure.com/acme, project: X }
                    repos:
                      - { id: web, provider: github, url: https://github.com/acme/web, primary: true }
                    gates:
                      G1: { roles: [PO, SquadLead], sod: true }
                """;
        PdlcConfig config = PdlcConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        Profile meta = config.profile("meta");
        assertThat(meta.project().name()).isEqualTo("Payments");
        assertThat(meta.project().confluenceUrl()).isEqualTo("https://acme.atlassian.net/wiki/spaces/PAY");
        assertThat(meta.project().brief()).isEqualTo("Kitchen ticket fidelity pilot");
        assertThat(meta.project().docs()).hasSize(1);
        assertThat(meta.project().docs().get(0).title()).isEqualTo("Tech stack");
        assertThat(meta.project().docs().get(0).url()).isEqualTo("https://acme/wiki/tech-stack");
        assertThat(meta.project().build().acpAgent()).isEqualTo("my acp agent");

        ProjectDocument doc = config.document("meta");
        assertThat(doc.project().name()).isEqualTo("Payments");
        assertThat(doc.repos()).hasSize(1);
        assertThat(doc.repos().get(0).id()).isEqualTo("web");
    }
}
