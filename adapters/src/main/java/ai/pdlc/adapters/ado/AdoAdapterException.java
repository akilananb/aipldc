package ai.pdlc.adapters.ado;

public class AdoAdapterException extends RuntimeException {
    public AdoAdapterException(String message) {
        super(message);
    }

    public AdoAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
