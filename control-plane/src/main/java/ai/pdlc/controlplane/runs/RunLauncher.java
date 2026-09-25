package ai.pdlc.controlplane.runs;

/** Starts and cancels {@code AgentRunWorkflow} executions; {@link TemporalRunLauncher} in production. */
public interface RunLauncher {

    void start(String workflowId, String runId, int timeoutSeconds);

    /** Requests cancellation; the workflow records {@code CANCELLED} itself. */
    void cancel(String workflowId);

    /** Tells a paused run that its approval was decided (the decision itself is in the DB). */
    void signalApproval(String workflowId, String approvalId);

    /** Tells a run waiting on an operator that the effect was resolved. */
    void signalEffect(String workflowId, String effectId);

    /** Tells a run waiting on a remote agent's input-required that an operator replied. */
    void signalInput(String workflowId, String messageId);
}
