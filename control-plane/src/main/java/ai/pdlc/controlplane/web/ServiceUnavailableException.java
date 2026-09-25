package ai.pdlc.controlplane.web;

/** A dependency (e.g. Temporal) is unreachable; mapped to 503 by {@link ApiExceptionHandler}. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
