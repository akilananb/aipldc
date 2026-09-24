package ai.pdlc.agents.platform;

import ai.pdlc.adapters.mcp.McpAuth;
import ai.pdlc.adapters.mcp.McpHttpClient;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.platform.McpFingerprint;
import ai.pdlc.core.platform.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP protocol side of {@link ToolExecutor} (slice 2.3): opens a session with the connection's
 * credentials, lists what the server offers now (cached per run segment) and checks it against the
 * reviewed definition, then calls the tool. Policy stays in the executor; this class never decides
 * whether a call may happen.
 */
@Component
public class McpToolCaller {

    private static final int LISTING_CACHE = 256;

    private final McpHttpClient client;
    private final McpCredentials credentials;
    private final Map<String, List<McpHttpClient.Tool>> listings = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<McpHttpClient.Tool>> eldest) {
                    return size() > LISTING_CACHE;
                }
            });

    @Autowired
    public McpToolCaller(EgressPolicy egress, McpCredentials credentials) {
        this(new McpHttpClient(egress::check), credentials);
    }

    McpToolCaller(McpHttpClient client, McpCredentials credentials) {
        this.client = client;
        this.credentials = credentials;
    }

    /** An open session plus the bearer to redact from results. */
    public record Session(McpHttpClient.Session session, McpAuth auth) {
        String secretToRedact() {
            return McpCredentials.bearerValue(auth);
        }
    }

    public Session open(McpCredentials.Connection connection, Duration timeout, int maxBytes) {
        McpAuth auth = credentials.auth(connection);
        return new Session(client.open(URI.create(connection.baseUrl()), auth, timeout, Math.max(maxBytes, 256_000)), auth);
    }

    /**
     * Why the server's current definition no longer matches what was reviewed (null = it matches):
     * the tool is gone, its fingerprint changed, or its schema differs from the pinned one.
     */
    public String driftProblem(Session session, String listingKey, ToolSpec spec) {
        List<McpHttpClient.Tool> offered = listings.computeIfAbsent(listingKey, k -> session.session().listTools());
        McpHttpClient.Tool current = offered.stream().filter(t -> t.name().equals(spec.mcpTool())).findFirst().orElse(null);
        if (current == null) {
            return "the MCP server no longer offers " + spec.mcpTool() + "; it needs re-review";
        }
        String fingerprint = McpFingerprint.of(current.name(), current.description(), current.inputSchema(), current.annotations());
        if (!fingerprint.equals(spec.mcpFingerprint())) {
            return "the MCP server changed " + spec.mcpTool() + " since it was reviewed; it needs re-review";
        }
        if (!ContentHash.canonicalJson(current.inputSchema()).equals(ContentHash.canonicalJson(spec.inputSchema()))) {
            return "the pinned input schema of " + spec.mcpTool() + " differs from the reviewed server schema";
        }
        return null;
    }

}
