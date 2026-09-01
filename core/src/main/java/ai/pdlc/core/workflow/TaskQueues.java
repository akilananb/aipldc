package ai.pdlc.core.workflow;

/** Task queue names — orchestration-decision §5 "Queues per role".
 *
 * <p>{@link AgentActivities} and the {@code FeatureWorkflow} workflow type itself are both hosted
 * by the {@code agents} module's worker, on {@link #REASONING}. {@link BoardSideEffects} is hosted
 * by control-plane's own, separate worker, on its own queue ({@link #BOARD}) - NOT the same queue
 * as agents, even though both ultimately serve "reasoning" work for the same workflow. A real bug
 * found running the pilot end-to-end: Temporal dispatches an activity task to *whichever* worker
 * process is currently polling a task queue, not to a worker smart-routed by registered activity
 * type; two worker processes on the same queue, each implementing a disjoint half of the
 * activities, means every single dispatch has a real chance of landing on the wrong worker, which
 * rejects it ("not registered") and forces Temporal's retry backoff (1s, 2s, 4s, ... capped at
 * 100s) until the lottery finally lands on the right one - multi-minute delays on what should be
 * sub-second activity calls, on nearly every step of every gate. Splitting the queue means each
 * worker is the *only* poller for its own activities, so every dispatch lands correctly the first
 * time.
 *
 * <p>{@link BuildActivities} (hosted by the {@code build-worker} Node process, since it shells out
 * to omp over ACP - a host-native capability control-plane/agents' JVMs don't have) polls
 * {@link #BUILD}, its own dedicated queue - never shared, no dispatch-mismatch risk there. */
public final class TaskQueues {
    public static final String REASONING = "reasoning";
    public static final String BOARD = "board";
    public static final String BUILD = "build";

    private TaskQueues() {
    }
}
