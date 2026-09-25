package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.Capability;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.core.workflow.A2aCardWorkflow;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The Studio's view of a remote A2A agent (docs/phase-2-execution-spec.md slice 2.5): the card's
 * name, negotiated protocol version, streaming support and skills, for picking the skill an a2a
 * agent delegates to. The connection must be usable by the workspace; the card is read by the
 * agents worker. Nothing here authorizes a run - the runner re-reads the card every time.
 */
@Service
public class A2aCardService {

    private final A2aCardClient client;
    private final ConnectionService connections;
    private final WorkspaceService workspaces;

    public A2aCardService(A2aCardClient client, ConnectionService connections, WorkspaceService workspaces) {
        this.client = client;
        this.connections = connections;
        this.workspaces = workspaces;
    }

    public A2aCardWorkflow.CardResult card(String workspaceId, String connectionId, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        List<String> problems = connections.toolConnectionProblems(connectionId, workspaceId, ConnectionService.A2A_AGENT);
        if (!problems.isEmpty()) {
            throw new ConflictException(String.join("; ", problems));
        }
        return client.read(connectionId);
    }
}
