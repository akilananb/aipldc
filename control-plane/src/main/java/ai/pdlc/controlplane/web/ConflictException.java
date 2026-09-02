package ai.pdlc.controlplane.web;

/** Thrown when a request conflicts with the current state of a resource (e.g. approving an
 * agent-mention result that is not {@code pending}). Mapped to HTTP 409 by {@link ApiExceptionHandler}. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
