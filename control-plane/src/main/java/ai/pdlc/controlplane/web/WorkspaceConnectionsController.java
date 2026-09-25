package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.connections.ConnectionService;
import ai.pdlc.controlplane.connections.A2aCardService;
import ai.pdlc.controlplane.connections.McpDiscoveryService;
import ai.pdlc.core.workflow.A2aCardWorkflow;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.web.dto.ConnectionDto;
import ai.pdlc.controlplane.web.dto.McpDiscoveryDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The HTTP_API and MCP_SERVER connections granted to a workspace, which its tools may bind (members; no secret refs). */
@RestController
public class WorkspaceConnectionsController {

    private final ConnectionService connections;
    private final McpDiscoveryService discovery;
    private final A2aCardService cards;
    private final IdentityResolver identityResolver;

    public WorkspaceConnectionsController(ConnectionService connections, McpDiscoveryService discovery, A2aCardService cards,
                                          IdentityResolver identityResolver) {
        this.connections = connections;
        this.discovery = discovery;
        this.cards = cards;
        this.identityResolver = identityResolver;
    }

    /** Lists a granted MCP server's tools with their review state (AUTHOR; docs/phase-2-execution-spec.md slice 2.3). */
    @PostMapping("/api/workspaces/{workspaceId}/connections/{connectionId}/mcp-discovery")
    public McpDiscoveryDto discover(@PathVariable String workspaceId, @PathVariable String connectionId, HttpServletRequest http) {
        return discovery.discover(workspaceId, connectionId, identityResolver.resolve(http));
    }

    /** Reads a granted A2A agent's card: name, protocol version and skills (AUTHOR; slice 2.5). */
    @PostMapping("/api/workspaces/{workspaceId}/connections/{connectionId}/a2a-card")
    public A2aCardWorkflow.CardResult a2aCard(@PathVariable String workspaceId, @PathVariable String connectionId,
                                              HttpServletRequest http) {
        return cards.card(workspaceId, connectionId, identityResolver.resolve(http));
    }

    @GetMapping("/api/workspaces/{workspaceId}/connections")
    public List<ConnectionDto> list(@PathVariable String workspaceId, HttpServletRequest http) {
        return connections.workspaceConnections(workspaceId, identityResolver.resolve(http));
    }
}
