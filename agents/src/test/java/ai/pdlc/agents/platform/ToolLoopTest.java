package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.ToolSpec;
import ai.pdlc.core.workflow.AgentRunActivities;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The runner's bounded tool loop with a scripted model and executor (docs/phase-2-execution-spec.md slice 2.1). */
class ToolLoopTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final ToolSpec GET_ORDER = new ToolSpec("Look up an order", "http", "orders-api", "GET",
            "/orders/{orderId}", Map.of("type", "object", "properties", Map.of("orderId", Map.of("type", "string"))),
            "READ", 5, 4096);

    private final RunStore runs = mock(RunStore.class);
    private final ToolStore tools = mock(ToolStore.class);
    private final ToolExecutor executor = mock(ToolExecutor.class);
    private final Deque<ModelInvoker.Reply> script = new ArrayDeque<>();
    private final List<List<ModelInvoker.Message>> histories = new ArrayList<>();
    private final List<List<ModelInvoker.ToolDef>> offered = new ArrayList<>();
    private final ModelInvoker model = (endpoint, history, defs, maxTokens, timeout) -> {
        histories.add(List.copyOf(history));
        offered.add(defs);
        return script.removeFirst();
    };
    private final StoredConversation stored = StoredConversation.attach(runs);
    private final AgentRunActivitiesImpl runner = new AgentRunActivitiesImpl(runs, ref -> "sk-test", model, tools, executor,
            mock(ai.pdlc.core.port.NotifyPort.class), (A2aRunner) null, (RestAgentRunner) null, (GrpcAgentRunner) null);

    private static AgentSpec agent(Integer maxTurns, Integer maxCalls) {
        return new AgentSpec("orders", "native", "Where is order {{input}}?",
                List.of(new AgentSpec.Variable("input", null, true)), new AgentSpec.ModelBinding("sonnet", List.of()),
                new AgentSpec.Limits(256, 30, maxTurns, maxCalls), null, List.of(new AgentSpec.ToolRef("get-order", 1)));
    }

    private void given(AgentSpec spec) {
        when(runs.load(RUN)).thenReturn(Optional.of(new Invocation(RUN, "engineering", "QUEUED", "order-bot", 1,
                ContentHash.ofAgent("Order bot", spec), "Order bot", ContentHash.canonicalJson(spec), Map.of("input", "42"),
                "sonnet", "anthropic/claude-sonnet-4", true, "gw", "ACTIVE", null, "API_KEY", "kv://llm-key",
                "https://gateway.example/v1", 2, 0)));
        when(runs.markRunning(RUN)).thenReturn(true);
        when(runs.pause(eq(RUN), any())).thenReturn(true);
        when(runs.complete(eq(RUN), any(), any(), any(), any())).thenReturn(true);
        when(tools.load("engineering", "get-order", 1)).thenReturn(Optional.of(new ToolStore.PinnedTool("engineering",
                "get-order", 1, "Get order", ContentHash.canonicalJson(GET_ORDER), ContentHash.ofTool("Get order", GET_ORDER),
                "ACTIVE", "orders-api", "HTTP_API", "ACTIVE", null, "API_KEY", "kv://orders-key", "https://api.example", true)));
    }

    private static ModelInvoker.Reply callsTool(String id, Integer promptTokens) {
        return new ModelInvoker.Reply(null, promptTokens, 1,
                List.of(new ModelInvoker.ToolCall(id, "get-order", "{\"orderId\":\"42\"}")));
    }

    @Test
    void runsModelTurnsThroughTheExecutorUntilAFinalAnswer() {
        given(agent(null, null));
        script.add(callsTool("c1", 10));
        script.add(new ModelInvoker.Reply("Order 42 has shipped.", 20, 5));
        when(executor.execute(any(), eq(Map.of("get-order", 1)), any()))
                .thenReturn(new ToolExecutor.Outcome(true, "{\"status\":200,\"body\":\"shipped\"}"));

        var outcome = runner.invoke(RUN.toString());

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        verify(runs).complete(RUN, "Order 42 has shipped.", null, 30, 6);
        assertThat(offered.get(0)).singleElement().satisfies(d -> {
            assertThat(d.name()).isEqualTo("get-order");
            assertThat(d.description()).isEqualTo("Look up an order");
        });
        assertThat(histories.get(1)).hasSize(3);
        assertThat(histories.get(1).get(2)).isEqualTo(new ModelInvoker.ToolResults(
                List.of(new ModelInvoker.ToolResult("c1", "get-order", "{\"status\":200,\"body\":\"shipped\"}"))));
        ArgumentCaptor<ToolExecutor.Context> context = ArgumentCaptor.forClass(ToolExecutor.Context.class);
        verify(executor).execute(context.capture(), any(), any());
        assertThat(context.getValue().attempt()).isEqualTo(3); // attempts column was 2; this is the third attempt
        assertThat(context.getValue().turn()).isEqualTo(1);
    }

    @Test
    void aDeniedCallIsReturnedToTheModelAndTheRunContinues() {
        given(agent(null, null));
        script.add(callsTool("c1", null));
        script.add(new ModelInvoker.Reply("I cannot cancel orders.", null, null));
        when(executor.execute(any(), any(), any()))
                .thenReturn(new ToolExecutor.Outcome(false, "{\"error\":\"denied by policy: requires an approval\"}"));

        assertThat(runner.invoke(RUN.toString()).status()).isEqualTo("SUCCEEDED");
        assertThat(((ModelInvoker.ToolResults) histories.get(1).get(2)).results().get(0).content()).contains("denied by policy");
        verify(runs).complete(RUN, "I cannot cancel orders.", null, null, null);
    }

    @Test
    void runningOutOfModelTurnsFailsTheRunInsteadOfSucceeding() {
        given(agent(2, null));
        script.add(callsTool("c1", 1));
        script.add(callsTool("c2", 1));
        when(executor.execute(any(), any(), any())).thenReturn(new ToolExecutor.Outcome(true, "{}"));

        assertThatThrownBy(() -> runner.invoke(RUN.toString()))
                .isInstanceOfSatisfying(ApplicationFailure.class, f -> {
                    assertThat(f.isNonRetryable()).isTrue();
                    assertThat(f.getType()).isEqualTo(AgentRunActivities.NON_RETRYABLE);
                })
                .hasMessageContaining("tool loop limit reached: maxModelTurns=2");
        verify(runs).fail(eq(RUN), startsWith("tool loop limit reached: maxModelTurns=2"), isNull());
        verify(runs, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void exceedingMaxToolCallsFailsBeforeTheExtraCallsRun() {
        given(agent(null, 2));
        script.add(new ModelInvoker.Reply(null, 1, 1, List.of(
                new ModelInvoker.ToolCall("a", "get-order", "{\"orderId\":\"1\"}"),
                new ModelInvoker.ToolCall("b", "get-order", "{\"orderId\":\"2\"}"),
                new ModelInvoker.ToolCall("c", "get-order", "{\"orderId\":\"3\"}"))));

        assertThatThrownBy(() -> runner.invoke(RUN.toString())).hasMessageContaining("maxToolCalls=2 would be exceeded");
        verify(executor, never()).execute(any(), any(), any());
        verify(runs).fail(eq(RUN), startsWith("tool loop limit reached: maxToolCalls=2"), isNull());
    }

    @Test
    void aPinnedToolThatNoLongerExistsRejectsTheRunBeforeAnyModelCall() {
        given(agent(null, null));
        when(tools.load("engineering", "get-order", 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runner.invoke(RUN.toString())).hasMessageContaining("Pinned tool get-order v1 does not exist");
        assertThat(histories).isEmpty();
    }

    private static final UUID APPROVAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void aWriteAwaitingApprovalPausesTheRunAndTheResumeContinuesWithoutAskingTheModelAgain() {
        given(agent(null, null));
        script.add(new ModelInvoker.Reply(null, 10, 1, List.of(
                new ModelInvoker.ToolCall("r1", "get-order", "{\"orderId\":\"42\"}"),
                new ModelInvoker.ToolCall("w1", "get-order", "{\"orderId\":\"43\"}"))));
        when(executor.execute(any(), any(), any()))
                .thenReturn(new ToolExecutor.Outcome(true, "{\"status\":200}"))
                .thenReturn(new ToolExecutor.Outcome(false, null, new ToolExecutor.Pause("AWAITING_APPROVAL", APPROVAL, 60, 1440)));

        var paused = runner.invoke(RUN.toString());

        assertThat(paused.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(paused.approvalId()).isEqualTo(APPROVAL.toString());
        assertThat(paused.escalateAfterMinutes()).isEqualTo(60);
        verify(runs).pause(RUN, "AWAITING_APPROVAL");
        verify(runs, never()).complete(any(), any(), any(), any(), any());
        assertThat(stored.kinds()).containsExactly("USER", "ASSISTANT", "TOOL_RESULT");

        // Approved: the next segment runs only the unanswered call, then asks the model once more.
        org.mockito.Mockito.reset(executor);
        when(executor.execute(any(), any(), any())).thenReturn(new ToolExecutor.Outcome(true, "{\"status\":201}"));
        script.add(new ModelInvoker.Reply("Done.", 20, 2));

        var outcome = runner.invoke(RUN.toString());

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        ArgumentCaptor<ModelInvoker.ToolCall> resumed = ArgumentCaptor.forClass(ModelInvoker.ToolCall.class);
        verify(executor).execute(any(), any(), resumed.capture());
        assertThat(resumed.getValue().id()).isEqualTo("w1");
        assertThat(histories).hasSize(2);
        assertThat(histories.get(1).get(2)).isEqualTo(new ModelInvoker.ToolResults(List.of(
                new ModelInvoker.ToolResult("r1", "get-order", "{\"status\":200}"),
                new ModelInvoker.ToolResult("w1", "get-order", "{\"status\":201}"))));
        verify(runs).complete(RUN, "Done.", null, 30, 3);
        assertThat(stored.kinds()).containsExactly("USER", "ASSISTANT", "TOOL_RESULT", "TOOL_RESULT", "ASSISTANT");
    }

    @Test
    void anUnknownWriteOutcomePausesForTheOperator() {
        given(agent(null, null));
        script.add(callsTool("w1", 1));
        UUID effect = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        when(executor.execute(any(), any(), any()))
                .thenReturn(new ToolExecutor.Outcome(false, null, new ToolExecutor.Pause("NEEDS_OPERATOR", effect, 0, 0)));

        var paused = runner.invoke(RUN.toString());

        assertThat(paused.status()).isEqualTo("NEEDS_OPERATOR");
        assertThat(paused.effectId()).isEqualTo(effect.toString());
        verify(runs).pause(RUN, "NEEDS_OPERATOR");
    }

    @Test
    void aSegmentThatCrashedAfterTheFinalAnswerFinishesFromTheStoredReply() {
        given(agent(null, null));
        script.add(new ModelInvoker.Reply("Stored answer.", 4, 2));
        when(runs.complete(eq(RUN), any(), any(), any(), any())).thenThrow(new RuntimeException("db down")).thenReturn(true);

        assertThatThrownBy(() -> runner.invoke(RUN.toString())).hasMessageContaining("db down");
        assertThat(runner.invoke(RUN.toString()).status()).isEqualTo("SUCCEEDED");
        assertThat(histories).hasSize(1);
    }

    @Test
    void activeTimeIsRecordedPerSegment() {
        given(agent(null, null));
        script.add(new ModelInvoker.Reply("ok", 1, 1));

        runner.invoke(RUN.toString());

        verify(runs).addActiveMs(eq(RUN), org.mockito.ArgumentMatchers.anyLong());
    }
}
