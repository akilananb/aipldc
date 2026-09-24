package ai.pdlc.controlplane.identity;

/** No authenticated caller (no session, token or dev headers) - mapped to HTTP 401 by {@code ApiExceptionHandler}. */
public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException(String message) {
        super(message);
    }
}
