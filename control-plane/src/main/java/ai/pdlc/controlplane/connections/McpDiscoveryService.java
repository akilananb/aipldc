package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.Capability;
import ai.pdlc.controlplane.platform.ToolRegistryService;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.dto.McpDiscoveryDto;
import ai.pdlc.core.platform.OutputSchema;
import ai.pdlc.core.workflow.McpDiscoveryWorkflow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Review support for remote MCP tools (docs/phase-2-execution-spec.md slice 2.3): lists what a
 * granted MCP server offers now and compares it with the workspace's approved tools. Approving is
 * the normal tool lifecycle - a {@code kind: mcp} draft pinning the reviewed fingerprint, then a
 * published version - so this service changes nothing itself.
 */
@Service
public class McpDiscoveryService {

    private final McpDiscoveryClient client;
    private final ConnectionService connections;
    private final ToolRegistryService tools;
    private final WorkspaceService workspaces;

    public McpDiscoveryService(McpDiscoveryClient client, ConnectionService connections, ToolRegistryService tools,
                               WorkspaceService workspaces) {
        this.client = client;
        this.connections = connections;
        this.tools = tools;
        this.workspaces = workspaces;
    }

    public McpDiscoveryDto discover(String workspaceId, String connectionId, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        List<String> problems = connections.toolConnectionProblems(connectionId, workspaceId, ConnectionService.MCP_SERVER);
        if (!problems.isEmpty()) {
            throw new ConflictException(String.join("; ", problems));
        }
        McpDiscoveryWorkflow.DiscoveryResult result = client.discover(connectionId);
        List<ToolRegistryService.McpBinding> bindings = tools.mcpBindings(workspaceId, connectionId);
        if (result.error() != null) {
            return new McpDiscoveryDto(connectionId, result.error(), List.of());
        }
        List<McpDiscoveryDto.Tool> out = new ArrayList<>();
        Set<String> offered = new HashSet<>();
        for (McpDiscoveryWorkflow.DiscoveredTool t : result.tools()) {
            offered.add(t.name());
            ToolRegistryService.McpBinding binding = bindings.stream().filter(b -> t.name().equals(b.mcpTool()))
                    .filter(b -> b.latestVersion() != null).findFirst()
                    .orElse(bindings.stream().filter(b -> t.name().equals(b.mcpTool())).findFirst().orElse(null));
            List<String> schemaProblems = schemaProblems(t.inputSchema());
            String state;
            if (!schemaProblems.isEmpty()) {
                state = "UNSUPPORTED_SCHEMA";
            } else if (binding == null || binding.latestFingerprint() == null) {
                state = "NEW";
            } else if (binding.latestFingerprint().equals(t.fingerprint())) {
                state = "APPROVED";
            } else {
                state = "CHANGED";
            }
            out.add(new McpDiscoveryDto.Tool(t.name(), t.description(), t.inputSchema(), t.annotations(), t.fingerprint(), state,
                    schemaProblems, binding == null ? null : binding.toolId(), binding == null ? null : binding.latestVersion(),
                    suggestedEffect(t.annotations())));
        }
        for (ToolRegistryService.McpBinding b : bindings) {
            if (b.latestVersion() != null && !offered.contains(b.mcpTool())) {
                out.add(new McpDiscoveryDto.Tool(b.mcpTool(), null, null, null, null, "REMOVED", List.of(), b.toolId(),
                        b.latestVersion(), null));
            }
        }
        return new McpDiscoveryDto(connectionId, null, out);
    }

    static List<String> schemaProblems(Map<String, Object> inputSchema) {
        if (inputSchema == null) {
            return List.of("the server declares no inputSchema");
        }
        List<String> problems = new ArrayList<>(OutputSchema.unsupported(inputSchema).stream()
                .map(e -> e.replace("outputSchema", "inputSchema")).toList());
        if (!"object".equals(inputSchema.get("type"))) {
            problems.add("inputSchema.type must be \"object\"");
        }
        return problems;
    }

    /** Hints are the server's claims: shown as a suggestion, never used to authorize. */
    static String suggestedEffect(Map<String, Object> annotations) {
        return annotations != null && Boolean.TRUE.equals(annotations.get("readOnlyHint")) ? "READ" : "WRITE";
    }
}
