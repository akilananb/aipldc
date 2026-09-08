package ai.pdlc.agents.tracing;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;

/** Trace-level Langfuse attributes for one agent run. {@link AgentRunTracer} stores the instance
 * directly on the root observation's {@link Observation.Context} ({@code context.put(AgentRunContext.class,
 * this)}) rather than a thread-local: {@link LangfuseObservationFilter} resolves it by walking
 * {@link Observation.ContextView#getParentObservation()} up to the root. A thread-local does not
 * work here — Spring AI's chat observation is created on the calling thread (so Micrometer's own
 * parent linkage, captured at creation time, is correct) but can be stopped from a different
 * thread (e.g. a reactive HTTP client's completion callback), by which point a thread-local set on
 * the original thread is invisible. */
public record AgentRunContext(String agent, String workflowId, String boardId, String workItemId, String environment) {

    /** Adds the langfuse.* attributes to {@code context}; null fields are skipped. */
    void stamp(Observation.Context context) {
        context.addHighCardinalityKeyValue(KeyValue.of("langfuse.trace.name", agent));
        context.addLowCardinalityKeyValue(KeyValue.of("langfuse.environment", environment));
        if (workflowId != null) {
            context.addHighCardinalityKeyValue(KeyValue.of("langfuse.session.id", workflowId));
            context.addHighCardinalityKeyValue(KeyValue.of("langfuse.trace.metadata.workflow_id", workflowId));
        }
        if (boardId != null) {
            context.addHighCardinalityKeyValue(KeyValue.of("langfuse.trace.metadata.board_id", boardId));
        }
        if (workItemId != null) {
            context.addHighCardinalityKeyValue(KeyValue.of("langfuse.trace.metadata.work_item_id", workItemId));
        }
    }
}
