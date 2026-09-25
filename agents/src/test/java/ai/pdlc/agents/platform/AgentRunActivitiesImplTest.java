package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.port.SecretsPort;
import ai.pdlc.core.workflow.AgentRunActivities;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunActivitiesImplTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final RunStore runs = mock(RunStore.class);
    private final SecretsPort secrets = ref -> "kv://llm-key".equals(ref) ? "sk-test" : missing(ref);
    private final List<String> prompts = new ArrayList<>();
    private final List<ModelInvoker.Endpoint> endpoints = new ArrayList<>();
    private ModelInvoker.Reply reply = new ModelInvoker.Reply("ALPHA", 12, 3);
    private RuntimeException providerError;
    private final ModelInvoker invoker = (endpoint, history, tools, maxTokens, timeout) -> {
        endpoints.add(endpoint);
        prompts.add(((ModelInvoker.UserMessage) history.get(0)).text());
        if (providerError != null) {
            throw providerError;
        }
        return reply;
    };
    private final AgentRunActivitiesImpl runner = new AgentRunActivitiesImpl(runs, secrets, invoker, mock(ToolStore.class),
            mock(ToolExecutor.class), mock(ai.pdlc.core.port.NotifyPort.class), (A2aRunner) null, (RestAgentRunner) null);

    private static String missing(String ref) {
        throw new IllegalStateException("No environment variable for " + ref);
    }

    static AgentSpec spec(Map<String, Object> outputSchema) {
        return new AgentSpec("labels", "native", "Return the label ALPHA for {{input}} & friends.",
                List.of(new AgentSpec.Variable("input", null, true)),
                new AgentSpec.ModelBinding("sonnet", List.of()), new AgentSpec.Limits(256, 30), outputSchema);
    }

    private Invocation invocation(AgentSpec spec, Map<String, String> inputs, boolean modelEnabled, String connectionStatus,
                                  String hashOverride) {
        String hash = hashOverride != null ? hashOverride : ContentHash.ofAgent("Labeler", spec);
        return new Invocation(RUN, "engineering", "QUEUED", "labeler", 1, hash, "Labeler",
                ContentHash.canonicalJson(spec), inputs, "sonnet", "anthropic/claude-sonnet-4", modelEnabled, "gw",
                connectionStatus, null, "API_KEY", "kv://llm-key", "https://gateway.example/v1", 0, 0);
    }

    private void given(Invocation invocation) {
        when(runs.load(RUN)).thenReturn(Optional.of(invocation));
        when(runs.markRunning(RUN)).thenReturn(true);
        when(runs.complete(eq(RUN), any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    void rendersThePinnedPromptCallsTheModelAndRecordsOutputAndUsage() {
        given(invocation(spec(null), Map.of("input", "order <42>"), true, "ACTIVE", null));

        var outcome = runner.invoke(RUN.toString());

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(prompts).containsExactly("Return the label ALPHA for order <42> & friends.");
        assertThat(endpoints.get(0).apiKey()).isEqualTo("sk-test");
        assertThat(endpoints.get(0).providerModel()).isEqualTo("anthropic/claude-sonnet-4");
        assertThat(endpoints.get(0).toString()).doesNotContain("sk-test");
        verify(runs).complete(RUN, "ALPHA", null, 12, 3);
    }

    @Test
    void aTamperedDefinitionIsRejectedWithoutCallingTheModel() {
        given(invocation(spec(null), Map.of("input", "x"), true, "ACTIVE", "sha256:deadbeef"));

        assertNonRetryable(() -> runner.invoke(RUN.toString()), "does not match its pinned hash");
        assertThat(prompts).isEmpty();
    }

    @Test
    void missingRequiredInputIsRejected() {
        given(invocation(spec(null), Map.of(), true, "ACTIVE", null));

        assertNonRetryable(() -> runner.invoke(RUN.toString()), "input \"input\" is required");
        assertThat(prompts).isEmpty();
    }

    @Test
    void aConnectionRevokedAfterStartStopsTheRunWithoutFallback() {
        given(invocation(spec(null), Map.of("input", "x"), true, "REVOKED", null));

        assertNonRetryable(() -> runner.invoke(RUN.toString()),
                "Model sonnet is unavailable at invocation: connection gw revoked");
        assertThat(prompts).isEmpty();
    }

    @Test
    void anUnresolvableSecretIsRejectedWithoutLeakingAnything() {
        Invocation base = invocation(spec(null), Map.of("input", "x"), true, "ACTIVE", null);
        given(new Invocation(base.runId(), base.workspaceId(), base.status(), base.agentId(), base.version(),
                base.contentHash(), base.name(), base.specJson(), base.inputs(), base.model(), base.providerModel(),
                true, "gw", "ACTIVE", null, "API_KEY", "kv://rotated-key", base.baseUrl(), 0, 0));

        assertNonRetryable(() -> runner.invoke(RUN.toString()),
                "Secret reference kv://rotated-key for connection gw could not be resolved");
    }

    @Test
    void aProviderErrorIsRetryable() {
        given(invocation(spec(null), Map.of("input", "x"), true, "ACTIVE", null));
        providerError = new IllegalStateException("503 Service Unavailable");

        assertThatThrownBy(() -> runner.invoke(RUN.toString()))
                .isInstanceOfSatisfying(ApplicationFailure.class, f -> {
                    assertThat(f.isNonRetryable()).isFalse();
                    assertThat(f.getOriginalMessage()).contains("503 Service Unavailable");
                });
    }

    @Test
    void outputIsValidatedAgainstTheSchemaAndAFencedJsonBlockIsAccepted() {
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("label"),
                "properties", Map.of("label", Map.of("type", "string")));
        given(invocation(spec(schema), Map.of("input", "x"), true, "ACTIVE", null));
        reply = new ModelInvoker.Reply("```json\n{\"label\":\"ALPHA\"}\n```", null, null);

        assertThat(runner.invoke(RUN.toString()).status()).isEqualTo("SUCCEEDED");
        verify(runs).complete(eq(RUN), anyString(), eq("{\"label\":\"ALPHA\"}"), isNull(), isNull());
    }

    @Test
    void outputThatBreaksTheSchemaFailsAndKeepsTheOutputForInspection() {
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("label"));
        given(invocation(spec(schema), Map.of("input", "x"), true, "ACTIVE", null));
        reply = new ModelInvoker.Reply("{\"answer\":\"ALPHA\"}", 5, 2);

        assertNonRetryable(() -> runner.invoke(RUN.toString()), "Output does not match outputSchema: $.label is required");
        verify(runs).fail(RUN, "Output does not match outputSchema: $.label is required", "{\"answer\":\"ALPHA\"}");
        verify(runs, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void anAlreadyTerminalRunIsNotInvokedAgain() {
        Invocation done = invocation(spec(null), Map.of("input", "x"), true, "ACTIVE", null);
        when(runs.load(RUN)).thenReturn(Optional.of(new Invocation(done.runId(), done.workspaceId(), "CANCELLED",
                done.agentId(), done.version(), done.contentHash(), done.name(), done.specJson(), done.inputs(),
                done.model(), done.providerModel(), true, "gw", "ACTIVE", null, "API_KEY", "kv://llm-key", done.baseUrl(), 0, 0)));
        when(runs.markRunning(RUN)).thenReturn(false);

        assertThat(runner.invoke(RUN.toString()).status()).isEqualTo("CANCELLED");
        assertThat(prompts).isEmpty();
    }

    @Test
    void aResultArrivingAfterCancellationIsDiscarded() {
        given(invocation(spec(null), Map.of("input", "x"), true, "ACTIVE", null));
        when(runs.complete(eq(RUN), any(), any(), any(), any())).thenReturn(false);

        assertThat(runner.invoke(RUN.toString()).status()).isEqualTo("QUEUED");
    }

    @Test
    void stripFenceOnlyUnwrapsASingleFence() {
        assertThat(AgentRunActivitiesImpl.stripFence("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(AgentRunActivitiesImpl.stripFence("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(AgentRunActivitiesImpl.stripFence("text ```x```")).isEqualTo("text ```x```");
    }

    private static void assertNonRetryable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApplicationFailure.class, f -> {
            assertThat(f.isNonRetryable()).isTrue();
            assertThat(f.getType()).isEqualTo(AgentRunActivities.NON_RETRYABLE);
            assertThat(f.getOriginalMessage()).contains(message);
        });
    }
}
