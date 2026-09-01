package ai.pdlc.adapters.secrets;

import ai.pdlc.core.port.SecretsPort;

import java.util.function.Function;

/**
 * Pilot {@link SecretsPort}: resolves {@code kv://NAME} refs to the environment variable
 * {@code NAME} (uppercased, dashes → underscores) — plan step 4.
 */
public final class EnvSecretsPort implements SecretsPort {

    private static final String SCHEME = "kv://";

    private final Function<String, String> envLookup;

    public EnvSecretsPort() {
        this(System::getenv);
    }

    /** Testing seam: inject a fake environment lookup. */
    public EnvSecretsPort(Function<String, String> envLookup) {
        this.envLookup = envLookup;
    }

    @Override
    public String resolve(String ref) {
        if (ref == null || !ref.startsWith(SCHEME)) {
            throw new IllegalArgumentException("SecretsPort only resolves kv:// refs, got: " + ref);
        }
        String name = ref.substring(SCHEME.length()).toUpperCase().replace('-', '_');
        String value = envLookup.apply(name);
        if (value == null) {
            throw new IllegalStateException("No environment variable " + name + " for secret ref " + ref);
        }
        return value;
    }
}
