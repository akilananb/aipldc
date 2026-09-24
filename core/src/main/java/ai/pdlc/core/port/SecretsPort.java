package ai.pdlc.core.port;

/** {@code kv://} refs only. Called only by adapters and execution adapters (e.g. the platform
 * runner resolving a connection's key just before a model call) - never by prompt/agent logic. */
public interface SecretsPort {

    String resolve(String ref);
}
