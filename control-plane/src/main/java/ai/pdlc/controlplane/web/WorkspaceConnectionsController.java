package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.connections.ConnectionService;
import ai.pdlc.controlplane.connections.McpDiscoveryService;
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
    private final IdentityResolver identityResolver;

    public WorkspaceConnectionsController(ConnectionService connections, McpDiscoveryService discovery,
                                          IdentityResolver identityResolver) {
        this.connections = connections;
        this.discovery = discovery;
        this.identityResolver = identityResolver;
    }

    /** Lists a granted MCP server's tools with their review state (AUTHOR; docs/phase-2-execution-spec.md slice 2.3). */
    @PostMapping("/api/workspaces/{workspaceId}/connections/{connectionId}/mcp-discovery")
    public McpDiscoveryDto discover(@PathVariable String workspaceId, @PathVariable String connectionId, HttpServletRequest http) {
        return discovery.discover(workspaceId, connectionId, identityResolver.resolve(http));
    }

    @GetMapping("/api/workspaces/{workspaceId}/connections")
    public List<ConnectionDto> list(@PathVariable String workspaceId, HttpServletRequest http) {
        return connections.workspaceConnections(workspaceId, identityResolver.resolve(http));
    }
}
