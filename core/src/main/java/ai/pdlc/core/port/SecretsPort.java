package ai.pdlc.core.port;

/** {@code kv://} refs only; agents never call this — only adapters, via config. */
public interface SecretsPort {

    String resolve(String ref);
}
