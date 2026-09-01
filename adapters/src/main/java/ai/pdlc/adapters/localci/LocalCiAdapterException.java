package ai.pdlc.adapters.localci;

/** Thrown when a {@link LocalCiAdapter} operation (git worktree, {@code npm test}, or its
 * deployment-marker file I/O) fails. */
public final class LocalCiAdapterException extends RuntimeException {

    public LocalCiAdapterException(String message) {
        super(message);
    }

    public LocalCiAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
