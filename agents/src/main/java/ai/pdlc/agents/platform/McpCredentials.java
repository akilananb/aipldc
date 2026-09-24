package ai.pdlc.agents.platform;

import ai.pdlc.adapters.mcp.McpAuth;
import ai.pdlc.adapters.mcp.McpOAuth;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.port.SecretsPort;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How the agents worker authenticates to an MCP server connection (docs/phase-2-execution-spec.md
 * slice 2.3): a static bearer from the connection's {@code kv://} reference, or OAuth client
 * credentials whose client secret is resolved only when a token is needed. Token holders are kept
 * per connection configuration, so a rotated secret or client id starts a fresh one.
 */
@Component
public class McpCredentials {

    /** The connection fields that decide authentication. */
    public record Connection(String id, String authType, String secretRef, String baseUrl, String oauthClientId) {
    }

    private final SecretsPort secrets;
    private final EgressPolicy egress;
    private final Map<String, McpOAuth> oauth = new ConcurrentHashMap<>();

    public McpCredentials(SecretsPort secrets, EgressPolicy egress) {
        this.secrets = secrets;
        this.egress = egress;
    }

    /** @throws IllegalStateException with a message naming the connection (never the secret) if it cannot be resolved */
    public McpAuth auth(Connection c) {
        return switch (c.authType() == null ? "NONE" : c.authType()) {
            case "API_KEY" -> McpAuth.bearer(resolve(c));
            case "OAUTH_CLIENT_CREDENTIALS" -> oauth.computeIfAbsent(
                    c.id() + "|" + c.baseUrl() + "|" + c.oauthClientId() + "|" + c.secretRef(),
                    k -> new McpOAuth(URI.create(c.baseUrl()), c.oauthClientId(), () -> resolve(c), egress::check, Duration.ofSeconds(15)));
            default -> McpAuth.NONE;
        };
    }

    private String resolve(Connection c) {
        String value;
        try {
            value = secrets.resolve(c.secretRef());
        } catch (RuntimeException e) {
            value = null;
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the credential for connection " + c.id() + " could not be resolved");
        }
        return value;
    }

    /** The bearer value currently sent, for redaction from anything returned to the model (null = none). */
    static String bearerValue(McpAuth auth) {
        String header = auth.header();
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
