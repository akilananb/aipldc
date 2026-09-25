package ai.pdlc.adapters.serviceauth;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Headers a service process (agents, build tools) attaches to calls into control-plane
 * (docs/phase-1-execution-spec.md slice 2). Production: a client-credentials JWT from the enterprise
 * IdP ({@link ClientCredentialsTokenSource}). Local stack: a static {@code X-Service-Token}, or
 * nothing when control-plane runs in dev-headers mode with no token configured.
 */
@FunctionalInterface
public interface ServiceCredentials extends Supplier<Map<String, String>> {

    ServiceCredentials NONE = Map::of;

    static ServiceCredentials bearer(ClientCredentialsTokenSource tokens) {
        return () -> Map.of("Authorization", "Bearer " + tokens.token());
    }

    static ServiceCredentials staticToken(String token) {
        return () -> Map.of("X-Service-Token", token);
    }
}
