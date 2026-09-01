package ai.pdlc.adapters.remoteboard;

/** Thrown when a {@link RemoteBoardPort} HTTP call to control-plane fails. */
public final class RemoteBoardPortException extends RuntimeException {
    public RemoteBoardPortException(String message) {
        super(message);
    }

    public RemoteBoardPortException(String message, Throwable cause) {
        super(message, cause);
    }
}
