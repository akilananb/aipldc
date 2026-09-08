package ai.pdlc.agents.tracing;

import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.WorkItemRef;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
/** Plain unit test (AGENTS.md pattern 3): no Spring context, no Docker. */
class AgentRunTracerTest {

    private Profile profile;
    private AgentRunTracer tracer;
    private WorkItemRef item;

    @BeforeEach
    void setUp() {
        profile = new Profile("local",
                new BoardConfig("in-memory", null, null, Map.of(), Map.of(), null),
                new RepoConfig("in-memory", "local://x", "main", "openspec"),
                new NotifyConfig("none", "none"),
                new AgentsConfig("http://stub", null, Map.of()),
                Map.of());
        tracer = new AgentRunTracer(ObservationRegistry.create(),
                new DefaultListableBeanFactory().getBeanProvider(Tracer.class),
                profile, "http://localhost:3000", "pdlc-pilot");
        item = new WorkItemRef("local", "4412");
    }

    @Test
    void bodyRunsAndTraceUrlIsNullWithoutTracer() {
        String result = tracer.trace("grill", item, null, "wf-1", run -> {
            assertThat(run.traceUrl()).isNull();
            assertThat(run.traceId()).isNull();
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void bodyRunsEvenWhenObservationErrors() {
        assertThatThrownBy(() -> tracer.trace("po", item, null, "wf-1", run -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }

    /** Regression test for the bug the parent-chain lookup replaced a thread-local for: Spring
     * AI's chat observation is created on the calling thread (so Micrometer's own parent link,
     * captured at creation, is correct) but its {@link org.springframework.ai.chat.observation.ChatModelObservationContext}
     * is filtered at {@code observation.stop()}, which for a reactive HTTP client can happen on a
     * different thread — a thread-local set on the original thread would be invisible there. A
     * child context with no run context of its own must still resolve one stored on an ancestor's
     * context via {@link Observation.ContextView#getParentObservation()}, regardless of thread.
     * Built from plain {@link Observation.Context}/{@link io.micrometer.observation.ObservationView}
     * objects (no registry): with zero {@link io.micrometer.observation.ObservationHandler}s
     * registered, {@code ObservationRegistry.create()} turns every observation into a no-op whose
     * context storage is inert, which would make a registry-backed version of this test pass
     * vacuously. */
    @Test
    void filterResolvesRunContextFromAncestorRegardlessOfThread() {
        Observation.Context rootContext = new Observation.Context();
        AgentRunContext runContext = new AgentRunContext("po", "wf-1", "4412", null, "local");
        rootContext.put(AgentRunContext.class, runContext);
        ObservationView rootView = () -> rootContext;

        Observation.Context childContext = new Observation.Context();
        childContext.setParentObservation(rootView);

        assertThat(LangfuseObservationFilter.resolveRunContext(childContext)).isSameAs(runContext);
    }

    @Test
    void filterResolutionReturnsNullWithNoAncestorRunContext() {
        Observation.Context orphanContext = new Observation.Context();
        assertThat(LangfuseObservationFilter.resolveRunContext(orphanContext)).isNull();
    }

    /** Same bug, exercised end-to-end through the real production path: a registry with an actual
     * {@link ObservationHandler} (so it isn't a no-op) and {@link LangfuseObservationFilter}
     * registered, {@link AgentRunTracer#trace} for the root, a nested child observation stopped on
     * a separate thread from the one that created it (simulating Spring AI's reactive HTTP client)
     * — proves both {@link AgentRunTracer}'s {@code context.put(...)} before {@code start()} and
     * the filter's parent-chain resolution work together, not just the isolated helper. */
    @Test
    void childObservationStoppedOnAnotherThreadStillCarriesTheRunsSessionId() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler((ObservationHandler<Observation.Context>) ctx -> true)
                .observationFilter(new LangfuseObservationFilter());
        AgentRunTracer realTracer = new AgentRunTracer(registry,
                new DefaultListableBeanFactory().getBeanProvider(Tracer.class),
                profile, "http://localhost:3000", "pdlc-pilot");

        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            Observation.Context childContext = realTracer.trace("po", item, null, "wf-thread-hop", run -> {
                Observation child = Observation.createNotStarted("chat anthropic/claude-sonnet-5", registry).start();
                try {
                    otherThread.submit(child::stop).get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return child.getContext();
            });

            KeyValue sessionId = childContext.getHighCardinalityKeyValue("langfuse.session.id");
            assertThat(sessionId).isNotNull();
            assertThat(sessionId.getValue()).isEqualTo("wf-thread-hop");
        } finally {
            otherThread.shutdown();
        }
    }

    @Test
    void traceUrlJoinsPublicUrlProjectAndTraceId() {
        assertThat(AgentRunTracer.traceUrl("http://localhost:3000/", "pdlc-pilot", "0af7651916cd43dd8448eb211c80319c"))
                .isEqualTo("http://localhost:3000/project/pdlc-pilot/traces/0af7651916cd43dd8448eb211c80319c");
        assertThat(AgentRunTracer.traceUrl("http://localhost:3000", "", "abc")).isNull();
        assertThat(AgentRunTracer.traceUrl("http://localhost:3000", "pdlc-pilot", null)).isNull();
    }
}
