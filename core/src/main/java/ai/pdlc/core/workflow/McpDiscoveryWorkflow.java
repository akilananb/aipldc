package ai.pdlc.core.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;
import java.util.Map;

/**
 * Lists a remote MCP server's tools for review (docs/phase-2-execution-spec.md slice 2.3). It runs
 * as a workflow so the call - and the connection's credential - stays in the agents worker:
 * control-plane starts it and waits for the result, and never resolves a secret itself. Tool
 * definitions are not sensitive, so they may appear in workflow history.
 */
@WorkflowInterface
public interface McpDiscoveryWorkflow {

    @WorkflowMethod
    DiscoveryResult discover(String connectionId);

    /** One server tool as offered right now, with its {@code McpFingerprint}. */
    record DiscoveredTool(String name, String description, Map<String, Object> inputSchema,
                          Map<String, Object> annotations, String fingerprint) {
    }

    /** {@code error} is set (and {@code tools} empty) when the server could not be listed. */
    record DiscoveryResult(List<DiscoveredTool> tools, String error) {
    }
}
