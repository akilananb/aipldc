package ai.pdlc.core.config;

/** Thrown at startup when {@code pdlc.yaml} is missing a profile or a required key. */
public class PdlcConfigException extends RuntimeException {
    public PdlcConfigException(String message) {
        super(message);
    }
}
