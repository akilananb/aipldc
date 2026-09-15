package ai.pdlc.adapters.localboard;

/** Thrown when a {@link LocalBoardAdapter} database operation fails. SQL failures always surface
 * here rather than being swallowed into an empty board. */
public final class LocalBoardAdapterException extends RuntimeException {

    public LocalBoardAdapterException(String message) {
        super(message);
    }

    public LocalBoardAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
