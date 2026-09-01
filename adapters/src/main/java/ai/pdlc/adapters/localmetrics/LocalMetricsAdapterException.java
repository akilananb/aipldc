package ai.pdlc.adapters.localmetrics;

/** Thrown when a {@link LocalMetricsAdapter} database operation fails. */
public final class LocalMetricsAdapterException extends RuntimeException {
    public LocalMetricsAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
