package ai.pdlc.adapters.localgit;

/** Thrown when a {@code git} plumbing command backing {@link LocalGitRepoAdapter} fails. */
public final class LocalGitAdapterException extends RuntimeException {

    public LocalGitAdapterException(String message) {
        super(message);
    }

    public LocalGitAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
