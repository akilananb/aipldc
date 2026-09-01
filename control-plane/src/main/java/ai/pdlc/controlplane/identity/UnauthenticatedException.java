package ai.pdlc.controlplane.identity;

/** Missing {@code X-User}/{@code X-Role} headers - mapped to HTTP 401 by {@code ApiExceptionHandler}. */
public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException(String message) {
        super(message);
    }
}
