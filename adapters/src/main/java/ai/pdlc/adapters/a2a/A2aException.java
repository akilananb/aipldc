package ai.pdlc.adapters.a2a;

/**
 * An A2A exchange failed. {@code code} is the JSON-RPC error code when the agent answered with one
 * (e.g. {@link #TASK_NOT_FOUND}, {@link #TASK_NOT_CANCELABLE}), else 0. {@code maybeSent} is true when
 * a message may have reached the agent although no answer arrived (timeout, broken connection, 5xx),
 * so the caller must reconcile rather than resend. Messages never contain credentials.
 */
public class A2aException extends RuntimeException {

    public static final int TASK_NOT_FOUND = -32001;
    public static final int TASK_NOT_CANCELABLE = -32002;
    public static final int UNSUPPORTED_OPERATION = -32004;
    public static final int VERSION_NOT_SUPPORTED = -32009;
    public static final int METHOD_NOT_FOUND = -32601;

    private final int code;
    private final boolean maybeSent;

    public A2aException(String message, boolean maybeSent) {
        this(message, 0, maybeSent);
    }

    public A2aException(String message, int code, boolean maybeSent) {
        super(message);
        this.code = code;
        this.maybeSent = maybeSent;
    }

    public int code() {
        return code;
    }

    public boolean maybeSent() {
        return maybeSent;
    }
}
