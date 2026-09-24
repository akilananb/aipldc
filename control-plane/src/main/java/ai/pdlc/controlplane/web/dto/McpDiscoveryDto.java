package ai.pdlc.controlplane.web.dto;

import java.util.List;
import java.util.Map;

/**
 * An MCP server's tools as offered now, each with its review state against the workspace's tools
 * (docs/phase-2-execution-spec.md slice 2.3). {@code error} is set when the server could not be listed.
 */
public record McpDiscoveryDto(String connectionId, String error, List<Tool> tools) {

    /**
     * {@code state}: NEW (never approved), APPROVED (latest published fingerprint matches),
     * CHANGED (differs: needs re-review), UNSUPPORTED_SCHEMA ({@code problems} say why), or REMOVED
     * (approved before, no longer offered; {@code fingerprint} null). {@code suggestedEffect} comes
     * from the server's hints and is only a suggestion - the reviewer decides.
     */
    public record Tool(String name, String description, Map<String, Object> inputSchema, Map<String, Object> annotations,
                       String fingerprint, String state, List<String> problems, String toolId, Integer approvedVersion,
                       String suggestedEffect) {
    }
}
