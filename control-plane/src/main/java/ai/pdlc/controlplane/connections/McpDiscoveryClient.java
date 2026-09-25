package ai.pdlc.controlplane.connections;

import ai.pdlc.core.workflow.McpDiscoveryWorkflow;

/** Lists an MCP server's tools through the agents worker (which holds the credentials); {@link TemporalMcpDiscoveryClient} in production. */
public interface McpDiscoveryClient {

    McpDiscoveryWorkflow.DiscoveryResult discover(String connectionId);
}
