package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.Capability;
import ai.pdlc.controlplane.platform.InMemoryWorkspaceStore;
import ai.pdlc.controlplane.platform.TestRegistries;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.ConnectionRequest;
import ai.pdlc.controlplane.web.dto.McpDiscoveryDto;
import ai.pdlc.controlplane.web.dto.ToolDraftRequest;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
import ai.pdlc.core.platform.McpFingerprint;
import ai.pdlc.core.platform.ToolSpec;
import ai.pdlc.core.workflow.McpDiscoveryWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MCP review states (docs/phase-2-execution-spec.md slice 2.3) over a fake discovery client. */
class McpDiscoveryServiceTest {

    static final Identity ADMIN = new Identity("it@acme", "Admin");
    static final Identity LEAD = new Identity("lead@acme", "SquadLead");
    static final Identity AUTHOR = new Identity("author@acme", "FSDeveloper");
    static final Identity OPERATOR = new Identity("op@acme", "QA");
    static final Identity FIN = new Identity("fin@acme", "SquadLead");
    static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of("orderId", Map.of("type", "string")));

    private final WorkspaceService workspaces = new WorkspaceService(new InMemoryWorkspaceStore());
    private final InMemoryConnectionStore store = InMemoryConnectionStore.withModels("gw", "sonnet");
    private final TestRegistries.Registries r = TestRegistries.over(workspaces, store);
    private final List<McpDiscoveryWorkflow.DiscoveredTool> offered = new ArrayList<>();
    private String listingError;
    private final McpDiscoveryService service = new McpDiscoveryService(
            id -> new McpDiscoveryWorkflow.DiscoveryResult(listingError == null ? List.copyOf(offered) : List.of(), listingError),
            r.connections(), r.tools(), workspaces);

    @BeforeEach
    void seed() {
        workspaces.create(new WorkspaceRequest("ops", "Ops", List.of(LEAD.user())), ADMIN);
        workspaces.create(new WorkspaceRequest("finance", "Finance", List.of(FIN.user())), ADMIN);
        workspaces.setMember("ops", AUTHOR.user(), EnumSet.of(Capability.AUTHOR), LEAD);
        workspaces.setMember("ops", OPERATOR.user(), EnumSet.of(Capability.OPERATOR), LEAD);
        r.connections().createConnection(new ConnectionRequest("orders-mcp", "MCP_SERVER", "OAUTH_CLIENT_CREDENTIALS",
                "kv://orders-mcp-secret", "https://api.example/mcp", null, "platform-client"), ADMIN);
        r.connections().grant("orders-mcp", "ops", ADMIN);
    }

    private static McpDiscoveryWorkflow.DiscoveredTool tool(String name, String description, Map<String, Object> schema,
                                                            Map<String, Object> annotations) {
        return new McpDiscoveryWorkflow.DiscoveredTool(name, description, schema, annotations,
                McpFingerprint.of(name, description, schema, annotations));
    }

    private void approve(McpDiscoveryWorkflow.DiscoveredTool t, String id) {
        var created = r.tools().create("ops", new ToolDraftRequest(id, id, new ToolSpec(t.description(), "mcp", "orders-mcp",
                null, null, t.inputSchema(), "READ", 10, 4096, null, null, t.name(), t.fingerprint()), null), AUTHOR);
        r.tools().publish("ops", id, created.draftRevision(), LEAD);
    }

    @Test
    void classifiesEveryServerToolAgainstTheWorkspacesApprovals() {
        var lookup = tool("lookup_order", "Look up an order", SCHEMA, Map.of("readOnlyHint", true));
        var cancel = tool("cancel_order", "Cancel an order", SCHEMA, Map.of());
        var fancy = tool("fancy", "Uses oneOf", Map.of("type", "object", "oneOf", List.of()), Map.of());
        var gone = tool("old_tool", "Went away", SCHEMA, Map.of());
        approve(lookup, "lookup-order");
        approve(gone, "old-tool");
        var changedLookup = tool("lookup_order", "Look up AND cancel an order", SCHEMA, Map.of("readOnlyHint", true));
        offered.addAll(List.of(changedLookup, cancel, fancy));

        McpDiscoveryDto result = service.discover("ops", "orders-mcp", AUTHOR);

        assertThat(result.error()).isNull();
        assertThat(result.tools()).extracting(McpDiscoveryDto.Tool::name, McpDiscoveryDto.Tool::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple("lookup_order", "CHANGED"),
                org.assertj.core.groups.Tuple.tuple("cancel_order", "NEW"),
                org.assertj.core.groups.Tuple.tuple("fancy", "UNSUPPORTED_SCHEMA"),
                org.assertj.core.groups.Tuple.tuple("old_tool", "REMOVED"));
        assertThat(result.tools().get(0).toolId()).isEqualTo("lookup-order");
        assertThat(result.tools().get(0).approvedVersion()).isEqualTo(1);
        assertThat(result.tools().get(0).suggestedEffect()).isEqualTo("READ");
        assertThat(result.tools().get(1).suggestedEffect()).isEqualTo("WRITE");
        assertThat(result.tools().get(2).problems()).singleElement().asString().contains("oneOf");

        offered.clear();
        offered.add(lookup);
        assertThat(service.discover("ops", "orders-mcp", AUTHOR).tools())
                .filteredOn(t -> t.name().equals("lookup_order")).singleElement()
                .extracting(McpDiscoveryDto.Tool::state).isEqualTo("APPROVED");
    }

    @Test
    void onlyAuthorsOfAWorkspaceTheServerIsGrantedToCanDiscover() {
        assertThatThrownBy(() -> service.discover("ops", "orders-mcp", OPERATOR)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.discover("finance", "orders-mcp", AUTHOR)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.discover("finance", "orders-mcp", FIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining("not granted to workspace finance");
        r.connections().revokeConnection("orders-mcp", ADMIN);
        assertThatThrownBy(() -> service.discover("ops", "orders-mcp", AUTHOR)).hasMessageContaining("revoked");
    }

    @Test
    void aServerThatCannotBeListedReportsTheReason() {
        listingError = "the MCP server rejected the connection's credentials (401)";

        McpDiscoveryDto result = service.discover("ops", "orders-mcp", AUTHOR);

        assertThat(result.error()).contains("401");
        assertThat(result.tools()).isEmpty();
    }

    @Test
    void anHttpApiConnectionCannotBackAnMcpTool() {
        r.connections().createConnection(new ConnectionRequest("orders-api", "HTTP_API", "NONE", null, "https://api.example/v1",
                null), ADMIN);
        r.connections().grant("orders-api", "ops", ADMIN);
        var t = tool("lookup_order", "Look up", SCHEMA, Map.of());
        var created = r.tools().create("ops", new ToolDraftRequest("wrong-kind", "wrong", new ToolSpec(t.description(), "mcp",
                "orders-api", null, null, SCHEMA, "READ", 10, 4096, null, null, t.name(), t.fingerprint()), null), AUTHOR);

        assertThat(r.tools().validate("ops", "wrong-kind", AUTHOR).errors())
                .containsExactly("connection orders-api is not an MCP_SERVER connection");
        assertThatThrownBy(() -> service.discover("ops", "orders-api", AUTHOR)).hasMessageContaining("not an MCP_SERVER");
        assertThat(created).isNotNull();
    }
}
