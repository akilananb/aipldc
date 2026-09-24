package ai.pdlc.adapters.projects;

/** Thrown when a {@code projects} table read/write fails. */
public class JdbcProjectDirectoryException extends RuntimeException {
    public JdbcProjectDirectoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
