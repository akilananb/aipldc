package ai.pdlc.core.platform;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identity of an MCP server's tool definition as reviewed (docs/phase-2-execution-spec.md slice
 * 2.3): a canonical hash of its name, description, input schema and annotations. Discovery shows
 * a tool whose current fingerprint differs from the approved one as CHANGED, and the executor
 * refuses to call it until a re-reviewed version is published - a server cannot silently widen
 * what an approved tool does or claims.
 */
public final class McpFingerprint {

    private McpFingerprint() {
    }

    public static String of(String name, String description, Map<String, Object> inputSchema, Map<String, Object> annotations) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", name);
        content.put("description", description);
        content.put("inputSchema", inputSchema);
        content.put("annotations", annotations);
        return ContentHash.of(content);
    }
}
