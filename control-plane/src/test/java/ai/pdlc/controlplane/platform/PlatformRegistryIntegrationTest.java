package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.ConnectionStore;
import ai.pdlc.controlplane.connections.JdbcConnectionStore;
import ai.pdlc.controlplane.connections.ModelCatalog;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.AgentDefinitionDto;
import ai.pdlc.controlplane.web.dto.AgentDraftRequest;
import ai.pdlc.controlplane.web.dto.AgentVersionDto;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.EnumSet;
import java.util.List;

import static ai.pdlc.controlplane.platform.AgentRegistryServiceTest.AUTHOR;
import static ai.pdlc.controlplane.platform.AgentRegistryServiceTest.OPERATOR;
import static ai.pdlc.controlplane.platform.AgentRegistryServiceTest.labelSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Docker-dependent (Testcontainers Postgres + real Flyway migrations, see AGENTS.md's
 * Docker-unavailable exclusion list): {@link JdbcWorkspaceStore} and {@link JdbcAgentRegistryStore}
 * against the real {@code V15} schema, driven through the services. Like {@code BuildTaskLeaseTest},
 * no Spring context is booted.
 */
@Testcontainers
class PlatformRegistryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Identity ENTERPRISE_ADMIN = new Identity("it@acme", "Admin");
    static final Identity ENG_ADMIN = new Identity("eng-lead@acme", "SquadLead");
    static final Identity FIN_ADMIN = new Identity("fin-lead@acme", "SquadLead");

    static JdbcTemplate jdbc;
    static WorkspaceService workspaces;
    static AgentRegistryService agents;
    static JdbcConnectionStore connections;

    @BeforeAll
    static void migrate() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        connections = new JdbcConnectionStore(jdbc);
        connections.insertConnection(new ConnectionStore.ConnectionRow("gw", "ENTERPRISE", null, "MODEL_PROVIDER",
                "API_KEY", "kv://llm-key", "https://gateway.example/v1", "ACTIVE", null, null, "it@acme", null,
                "it@acme", null, null));
        connections.insertModel(new ConnectionStore.ModelRow("sonnet", "gw", "anthropic/claude-sonnet-4", "Sonnet",
                true, null, "it@acme"));
        ModelCatalog models = new ModelCatalog(connections);
        workspaces = new WorkspaceService(new JdbcWorkspaceStore(jdbc));
        agents = new AgentRegistryService(new JdbcAgentRegistryStore(jdbc), workspaces, models);

        workspaces.create(new WorkspaceRequest("engineering", "Engineering", List.of(ENG_ADMIN.user())), ENTERPRISE_ADMIN);
        workspaces.create(new WorkspaceRequest("finance", "Finance", List.of(FIN_ADMIN.user())), ENTERPRISE_ADMIN);
        workspaces.setMember("engineering", AUTHOR.user(), EnumSet.of(Capability.AUTHOR), ENG_ADMIN);
        workspaces.setMember("engineering", OPERATOR.user(), EnumSet.of(Capability.OPERATOR, Capability.REVIEWER), ENG_ADMIN);
    }

    @Test
    void membershipRoundTripsCapabilitiesAndScopesListing() {
        assertThat(workspaces.get("engineering", OPERATOR).capabilities())
                .containsExactlyInAnyOrder(Capability.OPERATOR, Capability.REVIEWER);
        assertThat(workspaces.list(OPERATOR)).extracting(w -> w.id()).containsExactly("engineering");
        assertThat(workspaces.list(ENTERPRISE_ADMIN)).extracting(w -> w.id()).contains("engineering", "finance");
        assertThatThrownBy(() -> workspaces.get("finance", OPERATOR)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void publishPinsImmutableVersionsAndDraftRevisionsGuardConcurrentEdits() {
        AgentDefinitionDto created = agents.create("engineering",
                new AgentDraftRequest("labeler", "Labeler", labelSpec("ALPHA"), null), AUTHOR);
        assertThat(created.draftRevision()).isEqualTo(1);
        assertThat(created.draftSpec()).isEqualTo(labelSpec("ALPHA"));

        AgentVersionDto v1 = agents.publish("engineering", "labeler", 1, ENG_ADMIN);
        AgentVersionDto pinned = agents.resolveForRun("engineering", "labeler", OPERATOR).definition();

        AgentDefinitionDto edited = agents.saveDraft("engineering", "labeler",
                new AgentDraftRequest(null, "Labeler", labelSpec("BETA"), 1), AUTHOR);
        assertThat(edited.draftRevision()).isEqualTo(2);
        assertThatThrownBy(() -> agents.saveDraft("engineering", "labeler",
                new AgentDraftRequest(null, "Labeler", labelSpec("GAMMA"), 1), AUTHOR))
                .isInstanceOf(ConflictException.class);

        AgentVersionDto v2 = agents.publish("engineering", "labeler", 2, ENG_ADMIN);

        assertThat(pinned.version()).isEqualTo(1);
        assertThat(pinned.contentHash()).isEqualTo(v1.contentHash());
        assertThat(v2.version()).isEqualTo(2);
        assertThat(agents.resolveForRun("engineering", "labeler", OPERATOR).definition().spec().prompt()).contains("BETA");
        assertThat(agents.version("engineering", "labeler", 1, OPERATOR).spec()).isEqualTo(labelSpec("ALPHA"));
        assertThat(agents.versions("engineering", "labeler", OPERATOR)).extracting(AgentVersionDto::version).containsExactly(1, 2);
        assertThat(v1.publishedAt()).isNotNull();
        assertThat(v1.publishedBy()).isEqualTo(ENG_ADMIN.user());

        agents.rollback("engineering", "labeler", 1, ENG_ADMIN);
        assertThat(agents.resolveForRun("engineering", "labeler", OPERATOR).definition().version()).isEqualTo(1);

        assertThatThrownBy(() -> agents.get("engineering", "labeler", FIN_ADMIN)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aTamperedVersionRowFailsResolutionInsteadOfRunning() {
        AgentDefinitionDto created = agents.create("engineering",
                new AgentDraftRequest("tamper", "Tamper", labelSpec("SAFE"), null), AUTHOR);
        agents.publish("engineering", "tamper", created.draftRevision(), ENG_ADMIN);

        jdbc.update("UPDATE agent_definition_versions SET spec_json = replace(spec_json, 'SAFE', 'EVIL') WHERE agent_id = 'tamper'");

        assertThatThrownBy(() -> agents.resolveForRun("engineering", "tamper", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match its hash");
    }

    @Test
    void theJdbcCatalogResolvesProviderModelsAndRecordsImportsOnce() {
        AgentDefinitionDto created = agents.create("engineering",
                new AgentDraftRequest("catalog", "Catalog", labelSpec("C"), null), AUTHOR);
        agents.publish("engineering", "catalog", created.draftRevision(), ENG_ADMIN);

        var resolved = agents.resolveForRun("engineering", "catalog", OPERATOR);

        assertThat(resolved.providerModel()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(resolved.connectionId()).isEqualTo("gw");
        assertThat(connections.recordImport("test-import", "x")).isTrue();
        assertThat(connections.recordImport("test-import", "x")).isFalse();
    }

    @Test
    void theJdbcRunStorePinsAndGuardsRuns() {
        AgentDefinitionDto created = agents.create("engineering",
                new AgentDraftRequest("runnable", "Runnable", labelSpec("R"), null), AUTHOR);
        agents.publish("engineering", "runnable", created.draftRevision(), ENG_ADMIN);
        var runs = new ai.pdlc.controlplane.runs.JdbcRunStore(jdbc);
        java.util.UUID id = java.util.UUID.randomUUID();
        var row = new ai.pdlc.controlplane.runs.RunStore.RunRow(id, "engineering", "runnable", 1, "sha256:x", "sonnet",
                "anthropic/claude-sonnet-4", "gw", false, "{\"input\":\"x\"}", "QUEUED", null, null, null, null, null,
                0, "key-1", "agent-run-" + id, "op@acme", null, null, null);

        assertThat(runs.insert(row)).isTrue();
        assertThat(runs.insert(new ai.pdlc.controlplane.runs.RunStore.RunRow(java.util.UUID.randomUUID(), "engineering",
                "runnable", 1, "sha256:x", "sonnet", "p", "gw", false, "{}", "QUEUED", null, null, null, null, null, 0,
                "key-1", "wf", "op@acme", null, null, null))).isFalse();
        assertThat(runs.findByIdempotencyKey("engineering", "key-1")).get().extracting(r -> r.id()).isEqualTo(id);
        assertThat(runs.find(id)).get().satisfies(r -> {
            assertThat(r.status()).isEqualTo("QUEUED");
            assertThat(r.createdAt()).isNotNull();
        });
        runs.failToStart(id, "no temporal");
        assertThat(runs.find(id)).get().extracting(r -> r.status()).isEqualTo("FAILED");
        assertThat(runs.list("engineering", "runnable", 10)).hasSize(1);
    }
}
