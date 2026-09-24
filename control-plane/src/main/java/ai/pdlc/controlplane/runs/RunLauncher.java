package ai.pdlc.controlplane.runs;

/** Starts and cancels {@code AgentRunWorkflow} executions; {@link TemporalRunLauncher} in production. */
public interface RunLauncher {

    void start(String workflowId, String runId, int timeoutSeconds);

    /** Requests cancellation; the workflow records {@code CANCELLED} itself. */
    void cancel(String workflowId);
}
