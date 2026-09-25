package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.InMemoryConnectionStore;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.AgentDraftRequest;
import ai.pdlc.controlplane.web.dto.ConnectionRequest;
import ai.pdlc.controlplane.web.dto.ToolDefinitionDto;
import ai.pdlc.controlplane.web.dto.ToolDraftRequest;
import ai.pdlc.controlplane.web.dto.ToolVersionDto;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ToolSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static ai.pdlc.controlplane.platform.AgentRegistryServiceTest.AUTHOR;
import static ai.pdlc.controlplane.platform.AgentRegistryServiceTest.OPERATOR;
import static ai.pdlc.controlplane.platform.AgentRegistryServiceTest.labelSpec;
import static ai.pdlc.controlplane.platform.WorkspaceServiceTest.ENG_ADMIN;
import static ai.pdlc.controlplane.platform.WorkspaceServiceTest.ENTERPRISE_ADMIN;
import static ai.pdlc.controlplane.platform.WorkspaceServiceTest.FIN_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tool registry lifecycle, grant rules and agent tool pins (docs/phase-2-execution-spec.md slice 2.1). */
class ToolRegistryServiceTest {

    private final WorkspaceService workspaces = new WorkspaceService(new InMemoryWorkspaceStore());
    private final InMemoryConnectionStore connectionStore = InMemoryConnectionStore.withModels("gw", "sonnet");
    private final TestRegistries.Registries r = TestRegistries.over(workspaces, connectionStore);

    @BeforeEach
    void seed() {
        workspaces.create(new WorkspaceRequest("engineering", "Engineering", List.of(ENG_ADMIN.user())), ENTERPRISE_ADMIN);
        workspaces.create(new WorkspaceRequest("finance", "Finance", List.of(FIN_ADMIN.user())), ENTERPRISE_ADMIN);
        workspaces.setMember("engineering", AUTHOR.user(), EnumSet.of(Capability.AUTHOR), ENG_ADMIN);
        workspaces.setMember("engineering", OPERATOR.user(), EnumSet.of(Capability.OPERATOR), ENG_ADMIN);
        r.connections().createConnection(new ConnectionRequest("orders-api", "HTTP_API", "API_KEY", "kv://orders-key",
                "https://api.example/v1", null), ENTERPRISE_ADMIN);
    }

    static ToolSpec getOrder(String connectionId) {
        return new ToolSpec("Look up an order", "http", connectionId, "GET", "/orders/{orderId}",
                Map.of("type", "object", "properties", Map.of("orderId", Map.of("type", "string")), "required", List.of("orderId")),
                "READ", 10, 4096);
    }

    private ToolVersionDto publishGetOrder() {
        r.connections().grant("orders-api", "engineering", ENTERPRISE_ADMIN);
        ToolDefinitionDto created = r.tools().create("engineering",
                new ToolDraftRequest("get-order", "Get order", getOrder("orders-api"), null), AUTHOR);
        return r.tools().publish("engineering", "get-order", created.draftRevision(), ENG_ADMIN);
    }

    private static AgentSpec withTools(List<AgentSpec.ToolRef> refs) {
        AgentSpec b = labelSpec("T");
        return new AgentSpec(b.description(), b.runtime(), b.prompt(), b.variables(), b.model(), b.limits(), b.outputSchema(), refs);
    }

    @Test
    void publishRequiresTheConnectionToBeGrantedToTheWorkspace() {
        ToolDefinitionDto created = r.tools().create("engineering",
                new ToolDraftRequest("get-order", "Get order", getOrder("orders-api"), null), AUTHOR);

        assertThat(r.tools().validate("engineering", "get-order", AUTHOR).errors())
                .containsExactly("connection orders-api is not granted to workspace engineering");
        assertThatThrownBy(() -> r.tools().publish("engineering", "get-order", created.draftRevision(), ENG_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);

        r.connections().grant("orders-api", "engineering", ENTERPRISE_ADMIN);
        ToolVersionDto v1 = r.tools().publish("engineering", "get-order", created.draftRevision(), ENG_ADMIN);

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.contentHash()).startsWith("sha256:");
        assertThat(v1.spec()).isEqualTo(getOrder("orders-api"));
    }

    @Test
    void aModelProviderConnectionCannotBackATool() {
        connectionStore.grant("gw", "engineering", "it@acme");
        ToolDefinitionDto created = r.tools().create("engineering",
                new ToolDraftRequest("sneaky", "Sneaky", getOrder("gw"), null), AUTHOR);

        assertThat(r.tools().validate("engineering", "sneaky", AUTHOR).errors())
                .containsExactly("connection gw is not an HTTP_API connection");
        assertThatThrownBy(() -> r.tools().publish("engineering", "sneaky", created.draftRevision(), ENG_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftsUseTheSameRevisionAndAuthorizationRulesAsAgents() {
        ToolDefinitionDto created = r.tools().create("engineering",
                new ToolDraftRequest("get-order", "Get order", getOrder("orders-api"), null), AUTHOR);
        r.tools().saveDraft("engineering", "get-order",
                new ToolDraftRequest(null, "Get order v2", getOrder("orders-api"), created.draftRevision()), AUTHOR);

        assertThatThrownBy(() -> r.tools().saveDraft("engineering", "get-order",
                new ToolDraftRequest(null, "Stale", getOrder("orders-api"), created.draftRevision()), AUTHOR))
                .isInstanceOf(ConflictException.class).hasMessageContaining("revision 2");
        assertThatThrownBy(() -> r.tools().publish("engineering", "get-order", 2, AUTHOR))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> r.tools().create("engineering",
                new ToolDraftRequest("x", "X", getOrder("orders-api"), null), OPERATOR)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> r.tools().get("engineering", "get-order", FIN_ADMIN)).isInstanceOf(NotFoundException.class);
        assertThat(r.tools().list("finance", FIN_ADMIN)).isEmpty();
    }

    @Test
    void anAgentCanPinOnlyPublishedToolsOfItsOwnWorkspace() {
        publishGetOrder();

        r.agents().create("engineering", new AgentDraftRequest("bot", "Bot", withTools(List.of(
                new AgentSpec.ToolRef("get-order", 2), new AgentSpec.ToolRef("missing", 1))), null), AUTHOR);
        assertThat(r.agents().validate("engineering", "bot", AUTHOR).errors()).containsExactly(
                "tool get-order has no published version 2",
                "tool missing does not exist in workspace engineering");

        workspaces.setMember("finance", AUTHOR.user(), EnumSet.of(Capability.AUTHOR), FIN_ADMIN);
        r.agents().create("finance", new AgentDraftRequest("thief", "Thief", withTools(List.of(
                new AgentSpec.ToolRef("get-order", 1))), null), AUTHOR);
        assertThat(r.agents().validate("finance", "thief", AUTHOR).errors())
                .containsExactly("tool get-order does not exist in workspace finance");
    }

    @Test
    void revokingTheGrantOrTheConnectionOrRetiringTheToolBlocksNewRuns() {
        publishGetOrder();
        var created = r.agents().create("engineering", new AgentDraftRequest("bot", "Bot",
                withTools(List.of(new AgentSpec.ToolRef("get-order", 1))), null), AUTHOR);
        r.agents().publish("engineering", "bot", created.draftRevision(), ENG_ADMIN);
        assertThat(r.agents().resolveForRun("engineering", "bot", OPERATOR).definition().version()).isEqualTo(1);

        r.connections().revokeGrant("orders-api", "engineering", ENTERPRISE_ADMIN);
        assertThatThrownBy(() -> r.agents().resolveForRun("engineering", "bot", OPERATOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("tool get-order v1: connection orders-api is not granted to workspace engineering");

        r.connections().grant("orders-api", "engineering", ENTERPRISE_ADMIN);
        r.tools().retire("engineering", "get-order", ENG_ADMIN);
        assertThatThrownBy(() -> r.agents().resolveForRun("engineering", "bot", OPERATOR))
                .hasMessageContaining("tool get-order is retired");
    }

    @Test
    void revokingTheConnectionItselfBlocksNewRuns() {
        publishGetOrder();
        var created = r.agents().create("engineering", new AgentDraftRequest("bot", "Bot",
                withTools(List.of(new AgentSpec.ToolRef("get-order", 1))), null), AUTHOR);
        r.agents().publish("engineering", "bot", created.draftRevision(), ENG_ADMIN);

        r.connections().revokeConnection("orders-api", ENTERPRISE_ADMIN);

        assertThatThrownBy(() -> r.agents().resolveForRun("engineering", "bot", OPERATOR))
                .hasMessageContaining("connection orders-api is revoked");
    }

    @Test
    void agentsWithoutToolsAreUnaffected() {
        var created = r.agents().create("engineering", new AgentDraftRequest("plain", "Plain", labelSpec("P"), null), AUTHOR);
        r.agents().publish("engineering", "plain", created.draftRevision(), ENG_ADMIN);

        assertThat(r.agents().resolveForRun("engineering", "plain", OPERATOR).definition().spec().tools()).isNull();
    }
}
