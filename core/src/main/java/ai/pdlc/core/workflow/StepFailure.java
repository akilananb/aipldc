package ai.pdlc.core.workflow;

/** One currently-blocked reasoning/LLM step inside {@link FeatureWorkflow} — set when a bounded
 * activity retry budget (e.g. {@code AGENT_ACTIVITY_OPTIONS}'s {@code maxAttempts}) is exhausted,
 * cleared once a reviewer signals {@link FeatureWorkflow#retryStep}. {@code atEpochMilli} is
 * {@link io.temporal.workflow.Workflow#currentTimeMillis()}, not wall-clock, so replay stays
 * deterministic. */
public record StepFailure(String step, String message, long atEpochMilli) {
}
