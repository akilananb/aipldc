package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.connections.ConnectionService;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.web.dto.ConnectionDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The HTTP_API connections granted to a workspace, which its tools may bind (members; no secret refs). */
@RestController
public class WorkspaceConnectionsController {

    private final ConnectionService connections;
    private final IdentityResolver identityResolver;

    public WorkspaceConnectionsController(ConnectionService connections, IdentityResolver identityResolver) {
        this.connections = connections;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/api/workspaces/{workspaceId}/connections")
    public List<ConnectionDto> list(@PathVariable String workspaceId, HttpServletRequest http) {
        return connections.workspaceConnections(workspaceId, identityResolver.resolve(http));
    }
}
