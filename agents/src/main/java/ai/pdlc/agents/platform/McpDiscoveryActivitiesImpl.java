package ai.pdlc.agents.platform;

import ai.pdlc.adapters.mcp.McpException;
import ai.pdlc.adapters.mcp.McpHttpClient;
import ai.pdlc.core.platform.McpFingerprint;
import ai.pdlc.core.workflow.McpDiscoveryActivities;
import ai.pdlc.core.workflow.McpDiscoveryWorkflow;
import io.temporal.failure.ApplicationFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Lists an MCP server's tools for review (docs/phase-2-execution-spec.md slice 2.3) in the process
 * that holds credentials. Control-plane has already checked the caller and the workspace grant;
 * this re-checks that the connection is an active, unexpired MCP server before contacting it.
 */
@Component
public class McpDiscoveryActivitiesImpl implements McpDiscoveryActivities {

    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ToolStore tools;
    private final McpToolCaller mcp;
    private final Clock clock;

    @Autowired
    public McpDiscoveryActivitiesImpl(ToolStore tools, McpToolCaller mcp) {
        this(tools, mcp, Clock.systemUTC());
    }

    McpDiscoveryActivitiesImpl(ToolStore tools, McpToolCaller mcp, Clock clock) {
        this.tools = tools;
        this.mcp = mcp;
        this.clock = clock;
    }

    @Override
    public McpDiscoveryWorkflow.DiscoveryResult listTools(String connectionId) {
        ToolStore.ConnectionInfo c = tools.connection(connectionId).orElse(null);
        String problem = c == null ? "connection " + connectionId + " does not exist"
                : !"MCP_SERVER".equals(c.kind()) ? "connection " + connectionId + " is not an MCP_SERVER connection"
                : !"ACTIVE".equals(c.status()) ? "connection " + connectionId + " is revoked"
                : c.expiresAt() != null && !c.expiresAt().toInstant().isAfter(clock.instant()) ? "connection " + connectionId + " expired"
                : null;
        if (problem != null) {
            return new McpDiscoveryWorkflow.DiscoveryResult(List.of(), problem);
        }
        List<McpHttpClient.Tool> listed;
        try {
            McpToolCaller.Session session = mcp.open(
                    new McpCredentials.Connection(c.id(), c.authType(), c.secretRef(), c.baseUrl(), c.oauthClientId()), TIMEOUT, 256_000);
            listed = session.session().listTools();
        } catch (IllegalStateException e) {
            return new McpDiscoveryWorkflow.DiscoveryResult(List.of(), e.getMessage());
        } catch (McpException e) {
            throw ApplicationFailure.newFailure(e.getMessage(), "McpError");
        }
        return new McpDiscoveryWorkflow.DiscoveryResult(listed.stream().map(t -> new McpDiscoveryWorkflow.DiscoveredTool(
                t.name(), t.description(), t.inputSchema(), t.annotations(),
                McpFingerprint.of(t.name(), t.description(), t.inputSchema(), t.annotations()))).toList(), null);
    }
}
