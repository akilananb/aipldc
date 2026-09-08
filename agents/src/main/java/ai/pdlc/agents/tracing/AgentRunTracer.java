package ai.pdlc.agents.tracing;

import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.WorkItemRef;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Function;

/** One Micrometer observation ("pdlc.agent.run", span name {@code agent <name>}) per agent
 * activity; Spring AI's chat observation nests under it (Micrometer captures the parent link at
 * creation time, on the calling thread). Without a {@link Tracer} bean (tracing export off) the
 * body still runs and {@link Run#traceUrl()} is null. */
@Component
public class AgentRunTracer {

    /** {@code traceId}/{@code traceUrl} are null when tracing is off or the URL cannot be built. */
    public record Run(String traceId, String traceUrl) {
    }

    private final ObservationRegistry registry;
    private final ObjectProvider<Tracer> tracer;
    private final String environment;
    private final String publicUrl;
    private final String projectId;

    public AgentRunTracer(ObservationRegistry registry, ObjectProvider<Tracer> tracer, Profile activeProfile,
                          @Value("${langfuse.public-url:}") String publicUrl,
                          @Value("${langfuse.project-id:}") String projectId) {
        this.registry = registry;
        this.tracer = tracer;
        this.environment = activeProfile.name();
        this.publicUrl = publicUrl;
        this.projectId = projectId;
    }

    /** Exactly one of {@code item}/{@code workItemId} is non-null (mention flow carries the internal id). */
    public <T> T trace(String agent, WorkItemRef item, UUID workItemId, String workflowId, Function<Run, T> body) {
        AgentRunContext runContext = new AgentRunContext(agent, workflowId,
                item == null ? null : item.boardId(),
                workItemId == null ? null : workItemId.toString(),
                environment);
        Observation observation = Observation.createNotStarted("pdlc.agent.run", registry)
                .contextualName("agent " + agent)
                .lowCardinalityKeyValue("pdlc.agent", agent)
                .highCardinalityKeyValue("langfuse.observation.type", "agent");
        // Stored on the Context itself (not a thread-local) so LangfuseObservationFilter can
        // resolve it for a nested observation even if that observation is stopped on a different
        // thread than the one that created it.
        observation.getContext().put(AgentRunContext.class, runContext);
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            String traceId = currentTraceId();
            return body.apply(new Run(traceId, traceUrl(publicUrl, projectId, traceId)));
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    private String currentTraceId() {
        Tracer t = tracer.getIfAvailable();
        if (t == null) {
            return null;
        }
        Span span = t.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    /** {@code <publicUrl>/project/<projectId>/traces/<traceId>}; null when any part is blank. */
    static String traceUrl(String publicUrl, String projectId, String traceId) {
        if (isBlank(publicUrl) || isBlank(projectId) || isBlank(traceId)) {
            return null;
        }
        String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        return base + "/project/" + projectId + "/traces/" + traceId;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
