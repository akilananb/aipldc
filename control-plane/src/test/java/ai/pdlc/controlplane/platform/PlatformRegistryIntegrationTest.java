package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.ConnectionService;
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
 * Docker-unavailable exclusion list): {@link JdbcWorkspaceStore}, the JDBC definition stores,
 * connections/grants and runs against the real schema, driven through the services. Like {@code BuildTaskLeaseTest},
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
    static ConnectionService connectionService;
    static ToolRegistryService tools;

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
        connectionService = new ConnectionService(connections, models, workspaces, TestRegistries.egress(java.util.Set.of()));
        tools = new ToolRegistryService(new JdbcToolRegistryStore(jdbc), workspaces, connectionService,
                new ai.pdlc.controlplane.sandbox.SandboxImageService(new ai.pdlc.controlplane.sandbox.JdbcSandboxImageStore(jdbc)));
        agents = new AgentRegistryService(new JdbcAgentRegistryStore(jdbc), workspaces, models, tools, connectionService);

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

    @Test
    void workspaceProjectLinksAreIdempotentAndFollowProjectDeletion() {
        jdbc.update("INSERT INTO projects (id, name, config_json) VALUES ('proj-a', 'Project A', '{}'), ('proj-b', 'Project B', '{}')");
        JdbcWorkspaceStore store = new JdbcWorkspaceStore(jdbc);

        store.linkProject("engineering", "proj-a", "system:test");
        store.linkProject("engineering", "proj-a", "system:test");
        store.linkProject("engineering", "proj-b", "system:test");
        assertThat(store.projects("engineering")).extracting(WorkspaceStore.LinkedProject::name)
                .containsExactly("Project A", "Project B");

        jdbc.update("DELETE FROM projects WHERE id = 'proj-b'");
        assertThat(store.projects("engineering")).extracting(WorkspaceStore.LinkedProject::id).containsExactly("proj-a");
        assertThat(workspaces.projects("engineering", OPERATOR)).hasSize(1);
        assertThatThrownBy(() -> workspaces.projects("engineering", FIN_ADMIN)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void toolsPublishOnlyOverGrantedConnectionsAndAgentPinsFollowTheGrant() {
        connectionService.createConnection(new ai.pdlc.controlplane.web.dto.ConnectionRequest("orders-api", "HTTP_API",
                "API_KEY", "kv://orders-key", "https://api.example/v1", null), ENTERPRISE_ADMIN);
        ai.pdlc.core.platform.ToolSpec spec = new ai.pdlc.core.platform.ToolSpec("Look up an order", "http", "orders-api",
                "GET", "/orders/{orderId}", java.util.Map.of("type", "object",
                        "properties", java.util.Map.of("orderId", java.util.Map.of("type", "string")),
                        "required", List.of("orderId")), "READ", 10, 4096);
        var tool = tools.create("engineering", new ai.pdlc.controlplane.web.dto.ToolDraftRequest("get-order", "Get order",
                spec, null), AUTHOR);

        assertThatThrownBy(() -> tools.publish("engineering", "get-order", tool.draftRevision(), ENG_ADMIN))
                .hasMessageContaining("not granted to workspace engineering");
        assertThat(connectionService.grant("orders-api", "engineering", ENTERPRISE_ADMIN)).containsExactly("engineering");
        var v1 = tools.publish("engineering", "get-order", tool.draftRevision(), ENG_ADMIN);
        assertThat(v1.contentHash()).startsWith("sha256:");
        assertThat(connectionService.workspaceConnections("engineering", OPERATOR))
                .singleElement().satisfies(c -> assertThat(c.secretRef()).isNull());

        var base = labelSpec("T");
        var withTool = new ai.pdlc.core.platform.AgentSpec(base.description(), base.runtime(), base.prompt(), base.variables(),
                base.model(), base.limits(), base.outputSchema(), List.of(new ai.pdlc.core.platform.AgentSpec.ToolRef("get-order", 1)));
        var agent = agents.create("engineering", new AgentDraftRequest("order-bot", "Order bot", withTool, null), AUTHOR);
        agents.publish("engineering", "order-bot", agent.draftRevision(), ENG_ADMIN);
        assertThat(agents.resolveForRun("engineering", "order-bot", OPERATOR).definition().spec().toolsOrEmpty()).hasSize(1);

        connectionService.revokeGrant("orders-api", "engineering", ENTERPRISE_ADMIN);
        assertThatThrownBy(() -> agents.resolveForRun("engineering", "order-bot", OPERATOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("get-order v1: connection orders-api is not granted to workspace engineering");
    }

    @Test
    void theToolCallTraceIsReadInCallOrder() {
        AgentDefinitionDto created = agents.create("engineering",
                new AgentDraftRequest("traced", "Traced", labelSpec("X"), null), AUTHOR);
        agents.publish("engineering", "traced", created.draftRevision(), ENG_ADMIN);
        var runs = new ai.pdlc.controlplane.runs.JdbcRunStore(jdbc);
        java.util.UUID id = java.util.UUID.randomUUID();
        runs.insert(new ai.pdlc.controlplane.runs.RunStore.RunRow(id, "engineering", "traced", 1, "sha256:x", "sonnet",
                "p", "gw", false, "{}", "QUEUED", null, null, null, null, null, 0, null, "wf-" + id, "op@acme", null, null, null));
        jdbc.update("""
                INSERT INTO platform_tool_calls (run_id, attempt, turn, call_id, tool_id, tool_version, args_json, args_hash,
                    decision, reason, http_status, duration_ms, response_bytes, truncated)
                VALUES (?, 1, 1, 'c1', 'get-order', 1, '{}', 'sha256:a', 'ALLOWED', null, 200, 12, 40, false),
                       (?, 1, 2, 'c2', 'cancel-order', 1, '{}', 'sha256:b', 'DENIED', 'requires approval', null, null, null, false)""",
                id, id);

        assertThat(runs.toolCalls(id)).extracting(c -> c.toolId() + ":" + c.decision() + ":" + c.httpStatus())
                .containsExactly("get-order:ALLOWED:200", "cancel-order:DENIED:null");
    }

    @Test
    void theJdbcApprovalStoreGuardsDecisionsAndResolutionsOverTheV20Schema() {
        AgentDefinitionDto created = agents.create("engineering",
                new AgentDraftRequest("writer", "Writer", labelSpec("W"), null), AUTHOR);
        agents.publish("engineering", "writer", created.draftRevision(), ENG_ADMIN);
        var runs = new ai.pdlc.controlplane.runs.JdbcRunStore(jdbc);
        java.util.UUID run = java.util.UUID.randomUUID();
        runs.insert(new ai.pdlc.controlplane.runs.RunStore.RunRow(run, "engineering", "writer", 1, "sha256:x", "sonnet",
                "p", "gw", false, "{}", "QUEUED", null, null, null, null, null, 0, null, "wf-" + run, "op@acme", null, null, null));
        jdbc.update("UPDATE platform_runs SET status = 'AWAITING_APPROVAL' WHERE id = ?", run);
        java.util.UUID approval = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform_approvals (id, run_id, workspace_id, turn, call_id, tool_id, tool_version, args_json, args_hash)
                VALUES (?, ?, 'engineering', 1, 'c1', 'cancel-order', 1, '{"orderId":"42"}', 'sha256:a')""", approval, run);
        java.util.UUID effect = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform_effects (id, run_id, approval_id, tool_id, tool_version, args_hash, idempotency_key, state)
                VALUES (?, ?, ?, 'cancel-order', 1, 'sha256:a', ?, 'UNKNOWN')""", effect, run, approval, run + ":1:c1");
        var store = new ai.pdlc.controlplane.runs.JdbcApprovalStore(jdbc);

        assertThat(store.list("engineering", "PENDING")).singleElement().satisfies(a -> {
            assertThat(a.runCreatedBy()).isEqualTo("op@acme");
            assertThat(a.agentId()).isEqualTo("writer");
            assertThat(a.argsJson()).contains("42");
        });
        assertThat(store.list("finance", null)).isEmpty();
        assertThat(store.find("finance", approval)).isEmpty();
        assertThat(store.runWorkspace(run)).contains("engineering");
        assertThat(store.workflowId(run)).contains("wf-" + run);
        assertThat(store.decide(approval, "APPROVED", "reviewer@acme", "ok")).isTrue();
        assertThat(store.decide(approval, "REJECTED", "someone@acme", null)).isFalse();
        assertThat(store.find("engineering", approval)).get().extracting(a -> a.status()).isEqualTo("APPROVED");

        assertThat(store.resolve(effect, "RETRY", "op@acme", "safe to resend")).isTrue();
        assertThat(store.resolve(effect, "SUCCEEDED", "op@acme", null)).isFalse();
        assertThat(store.effects(run)).singleElement().satisfies(e -> {
            assertThat(e.state()).isEqualTo("INTENDED");
            assertThat(e.resolution()).isEqualTo("RETRY");
        });
        assertThatThrownBy(() -> jdbc.update("UPDATE platform_runs SET status = 'PAUSED' WHERE id = ?", run))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void theV22SandboxCatalogRoundTripsAndGuardsSandboxTools() {
        var images = new ai.pdlc.controlplane.sandbox.SandboxImageService(new ai.pdlc.controlplane.sandbox.JdbcSandboxImageStore(jdbc));
        String refV1 = "registry.acme/tools/order-report@sha256:" + "a".repeat(64);
        java.util.Map<String, Object> input = java.util.Map.of("type", "object",
                "properties", java.util.Map.of("orderId", java.util.Map.of("type", "string")));
        var request = new ai.pdlc.controlplane.web.dto.SandboxImageRequest("order-report", refV1, "Builds a report", input, null,
                List.of("api.example"), 500, 256, 60);
        images.create(request, ENTERPRISE_ADMIN);
        assertThat(images.get("order-report")).satisfies(i -> {
            assertThat(i.inputSchema()).isEqualTo(input);
            assertThat(i.outputSchema()).isNull();
            assertThat(i.egressHosts()).containsExactly("api.example");
            assertThat(i.createdBy()).isEqualTo("it@acme");
        });
        workspaces.setMember("engineering", "author@acme", java.util.EnumSet.of(Capability.AUTHOR), ENG_ADMIN);
        Identity author = new Identity("author@acme", "FSDeveloper");
        ai.pdlc.core.platform.ToolSpec spec = new ai.pdlc.core.platform.ToolSpec("Report", "sandbox", null, null, null, input,
                "READ", 30, 4096, null, null, null, null, "order-report", refV1);
        var created = tools.create("engineering", new ai.pdlc.controlplane.web.dto.ToolDraftRequest("report", "Report", spec, null),
                author);
        assertThat(tools.publish("engineering", "report", created.draftRevision(), ENG_ADMIN).version()).isEqualTo(1);

        images.update("order-report", new ai.pdlc.controlplane.web.dto.SandboxImageRequest(null,
                "registry.acme/tools/order-report@sha256:" + "b".repeat(64), "Builds a report", input, null, List.of(),
                500, 256, 60), ENTERPRISE_ADMIN);
        assertThat(tools.pinProblems("engineering", List.of(new ai.pdlc.core.platform.AgentSpec.ToolRef("report", 1))))
                .singleElement().asString().contains("needs re-review");
        images.retire("order-report", ENTERPRISE_ADMIN);
        assertThat(images.get("order-report").retiredBy()).isEqualTo("it@acme");
        assertThatThrownBy(() -> jdbc.update("UPDATE sandbox_images SET status = 'GONE'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void theV23SchemaHoldsA2aRunsTheirRemoteTaskAndOneReplyPerQuestion() {
        AgentDefinitionDto created = agents.create("engineering",
                new AgentDraftRequest("remote-ish", "Remote", labelSpec("R"), null), AUTHOR);
        agents.publish("engineering", "remote-ish", created.draftRevision(), ENG_ADMIN);
        var runs = new ai.pdlc.controlplane.runs.JdbcRunStore(jdbc);
        java.util.UUID id = java.util.UUID.randomUUID();
        assertThat(runs.insert(new ai.pdlc.controlplane.runs.RunStore.RunRow(id, "engineering", "remote-ish", 1, "sha256:x", null,
                null, "gw", false, "{}", "QUEUED", null, null, null, null, null, 0, null, "agent-run-" + id, "op@acme", null, null,
                null))).isTrue();
        assertThat(runs.acceptInput(id, "too early", "op@acme")).isEmpty();

        jdbc.update("UPDATE platform_runs SET status = 'AWAITING_INPUT' WHERE id = ?", id);
        jdbc.update("INSERT INTO platform_remote_tasks (run_id, dialect, task_id, context_id, state, status_text) VALUES (?, '1.0', 't-1', 'c-1', 'INPUT_REQUIRED', 'Which region?')", id);
        jdbc.update("INSERT INTO platform_run_messages (run_id, seq, kind, content_json) VALUES (?, 1, 'REMOTE_AGENT', '{\"text\":\"Which region?\"}')", id);
        jdbc.update("INSERT INTO platform_remote_sends (run_id, message_id, state) VALUES (?, ?, 'ACKED')", id, id + ":0");
        assertThat(runs.remote(id)).get().satisfies(r -> {
            assertThat(r.taskId()).isEqualTo("t-1");
            assertThat(r.statusText()).isEqualTo("Which region?");
        });

        assertThat(runs.acceptInput(id, "EMEA", "op@acme")).contains(2);
        assertThat(runs.acceptInput(id, "APAC", "op@acme")).isEmpty();
        assertThat(runs.find(id)).get().extracting(r -> r.status()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT content_json FROM platform_run_messages WHERE run_id = ? AND seq = 2", String.class, id))
                .contains("EMEA").contains("op@acme");
        jdbc.update("UPDATE platform_runs SET status = 'AWAITING_AUTH' WHERE id = ?", id);
        assertThatThrownBy(() -> jdbc.update("UPDATE platform_remote_tasks SET cancel = 'MAYBE' WHERE run_id = ?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO platform_remote_sends (run_id, message_id, state) VALUES (?, 'm', 'LOST')", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
