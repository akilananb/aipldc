package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.core.platform.AgentInputs;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.OutputSchema;
import ai.pdlc.core.platform.ToolSpec;
import ai.pdlc.core.port.NotifyPort;
import ai.pdlc.core.port.SecretsPort;
import ai.pdlc.core.workflow.AgentRunActivities;
import ai.pdlc.core.workflow.AgentRunWorkflow.AgentRunOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The native agent runner (docs/phase-1-execution-spec.md slice 4), hosted on
 * {@code TaskQueues.REASONING}. One {@link #invoke}:
 * <ol>
 *   <li>loads the run's pinned version and re-verifies its content hash;</li>
 *   <li>re-checks the inputs against the pinned variables;</li>
 *   <li>re-checks, <em>now</em>, that the pinned model is enabled and its connection active and
 *       unexpired - a revocation after the run started stops it here (no fallback: the model was
 *       chosen when the run started);</li>
 *   <li>resolves the connection's {@code kv://} secret reference (this process is the execution
 *       adapter; the value never leaves the call);</li>
 *   <li>renders the prompt, calls the model within the agent's deadline and output-token limit;</li>
 *   <li>validates the output against {@code outputSchema}, and records output and token usage
 *       ({@code null} = the provider did not report it).</li>
 * </ol>
 * An agent that pins tools (docs/phase-2-execution-spec.md slice 2.1) runs a bounded loop instead
 * of a single call: each model turn is offered the pinned tools' definitions, every requested call
 * goes through {@link ToolExecutor} (denials are returned to the model, not fatal), and the loop
 * ends when the model answers in text. Exceeding {@code maxModelTurns}, {@code maxToolCalls} or
 * the run deadline fails the run - it is never reported as success. A retry replays the loop;
 * only READ tools can execute in this slice, and each attempt's calls are recorded separately.
 * Anything retrying cannot fix is thrown non-retryable ({@link AgentRunActivities#NON_RETRYABLE});
 * provider errors are retryable once, by the workflow.
 */
@Component
public class AgentRunActivitiesImpl implements AgentRunActivities {

    private static final Logger log = LoggerFactory.getLogger(AgentRunActivitiesImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunStore runs;
    private final SecretsPort secrets;
    private final ModelInvoker models;
    private final ToolStore tools;
    private final ToolExecutor executor;
    private final NotifyPort notify;
    private final A2aRunner a2a;
    private final Clock clock;

    @Autowired
    public AgentRunActivitiesImpl(RunStore runs, SecretsPort secrets, ModelInvoker models, ToolStore tools,
                                  ToolExecutor executor, NotifyPort notify, A2aRunner a2a) {
        this(runs, secrets, models, tools, executor, notify, a2a, Clock.systemUTC());
    }

    AgentRunActivitiesImpl(RunStore runs, SecretsPort secrets, ModelInvoker models, ToolStore tools,
                           ToolExecutor executor, NotifyPort notify, Clock clock) {
        this(runs, secrets, models, tools, executor, notify, null, clock);
    }

    AgentRunActivitiesImpl(RunStore runs, SecretsPort secrets, ModelInvoker models, ToolStore tools,
                           ToolExecutor executor, NotifyPort notify, A2aRunner a2a, Clock clock) {
        this.a2a = a2a;
        this.runs = runs;
        this.secrets = secrets;
        this.models = models;
        this.tools = tools;
        this.executor = executor;
        this.notify = notify;
        this.clock = clock;
    }

    @Override
    public AgentRunOutcome invoke(String runId) {
        UUID id = UUID.fromString(runId);
        Invocation run = runs.load(id).orElseThrow(() -> reject("No run " + runId));
        if (!runs.markRunning(id)) {
            // Already terminal (a retry after success, or cancelled before this attempt): no model call.
            return new AgentRunOutcome(runId, runs.load(id).map(Invocation::status).orElse("UNKNOWN"), null);
        }

        AgentSpec spec = ContentHash.read(run.specJson(), AgentSpec.class);
        if (!ContentHash.ofAgent(run.name(), spec).equals(run.contentHash())) {
            throw reject("Agent " + run.agentId() + " v" + run.version() + " content does not match its pinned hash");
        }
        List<String> inputErrors = AgentInputs.validate(spec, run.inputs());
        if (!inputErrors.isEmpty()) {
            throw reject(String.join("; ", inputErrors));
        }
        if (spec.isA2a()) {
            if (a2a == null) {
                throw reject("a2a agents are not supported by this worker");
            }
            return a2a.invoke(run, spec, PromptRenderer.render(run.contentHash(), spec.prompt(), run.inputs()));
        }
        String unavailable = unavailable(run);
        if (unavailable != null) {
            throw reject("Model " + run.model() + " is unavailable at invocation: " + unavailable);
        }

        String apiKey;
        try {
            apiKey = "API_KEY".equals(run.authType()) ? secrets.resolve(run.secretRef()) : "none";
        } catch (RuntimeException e) {
            throw reject("Secret reference " + run.secretRef() + " for connection " + run.connectionId() + " could not be resolved");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "none"; // an endpoint without authentication (same convention as AgentsApplication)
        }

        String prompt = PromptRenderer.render(run.contentHash(), spec.prompt(), run.inputs());
        ModelInvoker.Endpoint endpoint = new ModelInvoker.Endpoint(run.baseUrl(), apiKey, run.providerModel());
        ModelInvoker.Reply reply;
        if (spec.toolsOrEmpty().isEmpty()) {
            reply = callModel(runId, run, () -> models.call(endpoint, prompt, spec.limits().maxOutputTokens(),
                    Duration.ofSeconds(spec.limits().timeoutSeconds())));
        } else {
            Segment segment = toolLoop(run, spec, endpoint, prompt);
            if (segment.pause() != null) {
                return segment.pause();
            }
            reply = segment.reply();
        }
        String text = reply.text() == null ? "" : reply.text();

        String outputJson = null;
        if (spec.outputSchema() != null) {
            JsonNode parsed;
            try {
                parsed = JSON.readTree(stripFence(text));
            } catch (Exception e) {
                runs.fail(id, "Output is not valid JSON for the declared outputSchema", text);
                throw reject("Output is not valid JSON for the declared outputSchema");
            }
            List<String> findings = OutputSchema.validate(spec.outputSchema(), parsed);
            if (!findings.isEmpty()) {
                String error = "Output does not match outputSchema: " + String.join("; ", findings);
                runs.fail(id, error, text);
                throw reject(error);
            }
            outputJson = parsed.toString();
        }

        if (!runs.complete(id, text, outputJson, reply.promptTokens(), reply.completionTokens())) {
            return new AgentRunOutcome(runId, runs.load(id).map(Invocation::status).orElse("UNKNOWN"), null);
        }
        return new AgentRunOutcome(runId, "SUCCEEDED", null);
    }

    /** How a tool-loop segment ended: a final reply, or a pause the workflow must wait out. */
    private record Segment(ModelInvoker.Reply reply, AgentRunOutcome pause) {
    }

    /** Stored ASSISTANT entry: the model's turn, with its token usage (null = unreported). */
    record StoredAssistant(String text, List<ModelInvoker.ToolCall> toolCalls, Integer promptTokens, Integer completionTokens) {
    }

    /**
     * The bounded, resumable tool-use loop (slices 2.1/2.2). The conversation is stored as it
     * grows - the model's turn before any of its calls run, each call's result as it completes - so
     * a retry or a resume after a pause continues exactly where the last segment stopped: answered
     * calls are not re-run and the model is not asked again. Turn and call counts, and token usage,
     * are rebuilt from the stored conversation, so the limits hold across pauses. Only active time
     * counts against the agent's timeout.
     */
    private Segment toolLoop(Invocation run, AgentSpec spec, ModelInvoker.Endpoint endpoint, String prompt) {
        String runId = run.runId().toString();
        Map<String, Integer> pinned = new LinkedHashMap<>();
        List<ModelInvoker.ToolDef> definitions = new ArrayList<>();
        for (AgentSpec.ToolRef ref : spec.toolsOrEmpty()) {
            ToolStore.PinnedTool tool = tools.load(run.workspaceId(), ref.tool(), ref.version())
                    .orElseThrow(() -> reject("Pinned tool " + ref.tool() + " v" + ref.version() + " does not exist"));
            ToolSpec toolSpec = ContentHash.read(tool.specJson(), ToolSpec.class);
            if (!ContentHash.ofTool(tool.name(), toolSpec).equals(tool.contentHash())) {
                throw reject("Pinned tool " + ref.tool() + " v" + ref.version() + " content does not match its hash");
            }
            pinned.put(ref.tool(), ref.version());
            definitions.add(new ModelInvoker.ToolDef(ref.tool(), toolSpec.description(), toolSpec.inputSchema()));
        }

        AgentSpec.Limits limits = spec.limits();
        Instant started = clock.instant();
        Instant deadline = started.plusMillis(limits.timeoutSeconds() * 1000L - run.activeMs());
        int attempt = run.attempts() + 1;
        try {
            Conversation c = Conversation.load(runs, run.runId(), prompt);
            while (true) {
                if (c.lastAssistant != null && !c.lastAssistant.toolCalls().isEmpty()) {
                    for (ModelInvoker.ToolCall call : c.unansweredCalls()) {
                        ToolExecutor.Outcome outcome = executor.execute(
                                new ToolExecutor.Context(run.runId(), run.workspaceId(), attempt, c.turn, deadline), pinned, call);
                        if (outcome.pause() != null) {
                            return new Segment(null, pause(run, outcome.pause()));
                        }
                        c.answer(call, outcome.content());
                    }
                    c.closeTurn();
                } else if (c.lastAssistant != null) {
                    return new Segment(new ModelInvoker.Reply(c.lastAssistant.text(), c.promptTokens, c.completionTokens), null);
                }
                if (c.turn >= limits.modelTurns()) {
                    throw loopLimit(run, "maxModelTurns=" + limits.modelTurns() + " reached without a final answer");
                }
                Duration remaining = Duration.between(clock.instant(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    throw loopLimit(run, "the run deadline of " + limits.timeoutSeconds() + "s passed");
                }
                ModelInvoker.Reply reply = callModel(runId, run,
                        () -> models.chat(endpoint, c.history, definitions, limits.maxOutputTokens(), remaining));
                c.addAssistant(reply);
                if (c.toolCalls > limits.toolCalls()) {
                    throw loopLimit(run, "maxToolCalls=" + limits.toolCalls() + " would be exceeded ("
                            + (c.toolCalls - reply.toolCalls().size()) + " made, " + reply.toolCalls().size() + " more requested)");
                }
            }
        } finally {
            runs.addActiveMs(run.runId(), Duration.between(started, clock.instant()).toMillis());
        }
    }

    private AgentRunOutcome pause(Invocation run, ToolExecutor.Pause pause) {
        String runId = run.runId().toString();
        if (!runs.pause(run.runId(), pause.kind())) {
            return new AgentRunOutcome(runId, runs.load(run.runId()).map(Invocation::status).orElse("UNKNOWN"), null);
        }
        log.info("Run {} paused: {} {}", runId, pause.kind(), pause.id());
        return ToolExecutor.AWAITING_APPROVAL.equals(pause.kind())
                ? AgentRunOutcome.awaitingApproval(runId, pause.id().toString(), pause.escalateAfterMinutes(),
                        pause.expireAfterMinutes())
                : AgentRunOutcome.needsOperator(runId, pause.id().toString());
    }

    /**
     * The stored conversation rebuilt into model history, plus the loop's counters. Appends go to
     * the store first; a position already taken means another attempt wrote it, which is a bug
     * (attempts of one run never overlap), so it fails loudly.
     */
    static final class Conversation {
        private final RunStore runs;
        private final UUID runId;
        final List<ModelInvoker.Message> history = new ArrayList<>();
        private final List<ModelInvoker.ToolResult> openResults = new ArrayList<>();
        StoredAssistant lastAssistant;
        int turn;
        int toolCalls;
        Integer promptTokens = 0;
        Integer completionTokens = 0;
        private int seq;

        private Conversation(RunStore runs, UUID runId) {
            this.runs = runs;
            this.runId = runId;
        }

        static Conversation load(RunStore runs, UUID runId, String prompt) {
            Conversation c = new Conversation(runs, runId);
            List<RunStore.StoredMessage> stored = runs.messages(runId);
            if (stored.isEmpty()) {
                c.append("USER", Map.of("text", prompt));
                c.history.add(new ModelInvoker.UserMessage(prompt));
                return c;
            }
            for (RunStore.StoredMessage m : stored) {
                c.seq = m.seq() + 1;
                switch (m.kind()) {
                    case "USER" -> c.history.add(new ModelInvoker.UserMessage(read(m.contentJson(), Map.class).get("text").toString()));
                    case "ASSISTANT" -> {
                        c.closeTurn();
                        c.addAssistantToHistory(read(m.contentJson(), StoredAssistant.class));
                    }
                    case "TOOL_RESULT" -> c.openResults.add(read(m.contentJson(), ModelInvoker.ToolResult.class));
                    default -> throw new IllegalStateException("Unknown stored message kind " + m.kind());
                }
            }
            if (c.lastAssistant != null && c.unansweredCalls().isEmpty()) {
                c.closeTurn();
            }
            return c;
        }

        void addAssistant(ModelInvoker.Reply reply) {
            StoredAssistant a = new StoredAssistant(reply.text(), reply.toolCalls(), reply.promptTokens(), reply.completionTokens());
            append("ASSISTANT", a);
            addAssistantToHistory(a);
        }

        private void addAssistantToHistory(StoredAssistant a) {
            turn++;
            toolCalls += a.toolCalls().size();
            promptTokens = sum(promptTokens, a.promptTokens());
            completionTokens = sum(completionTokens, a.completionTokens());
            lastAssistant = a;
            if (!a.toolCalls().isEmpty()) {
                history.add(new ModelInvoker.AssistantMessage(a.text(), a.toolCalls()));
            }
        }

        List<ModelInvoker.ToolCall> unansweredCalls() {
            Set<String> answered = new HashSet<>();
            openResults.forEach(r -> answered.add(r.callId()));
            return lastAssistant.toolCalls().stream().filter(call -> !answered.contains(call.id())).toList();
        }

        void answer(ModelInvoker.ToolCall call, String content) {
            ModelInvoker.ToolResult result = new ModelInvoker.ToolResult(call.id(), call.name(), content);
            append("TOOL_RESULT", result);
            openResults.add(result);
        }

        /** The last assistant turn's results, once complete, become one tool-results message. */
        void closeTurn() {
            if (!openResults.isEmpty()) {
                history.add(new ModelInvoker.ToolResults(List.copyOf(openResults)));
                openResults.clear();
            }
            if (lastAssistant != null && !lastAssistant.toolCalls().isEmpty()) {
                lastAssistant = null;
            }
        }

        private void append(String kind, Object content) {
            String json;
            try {
                json = JSON.writeValueAsString(content);
            } catch (Exception e) {
                throw new IllegalStateException("Conversation entry is not serializable", e);
            }
            if (!runs.appendMessage(runId, seq, kind, json)) {
                throw new IllegalStateException("Conversation position " + seq + " of run " + runId + " was already written");
            }
            seq++;
        }

        private static <T> T read(String json, Class<T> type) {
            try {
                return JSON.readValue(json, type);
            } catch (Exception e) {
                throw new IllegalStateException("Stored conversation entry is not valid " + type.getSimpleName(), e);
            }
        }
    }

    private ModelInvoker.Reply callModel(String runId, Invocation run, java.util.function.Supplier<ModelInvoker.Reply> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("Run {}: model call to {} failed: {}", runId, run.providerModel(), e.toString());
            throw ApplicationFailure.newFailure("Model call to " + run.providerModel() + " failed: " + e.getMessage(),
                    "ProviderError");
        }
    }

    private ApplicationFailure loopLimit(Invocation run, String detail) {
        String error = "tool loop limit reached: " + detail;
        runs.fail(run.runId(), error, null);
        return reject(error);
    }

    private static Integer sum(Integer total, Integer turn) {
        return total == null || turn == null ? null : total + turn;
    }

    @Override
    public void markFailed(String runId, String error) {
        runs.fail(UUID.fromString(runId), error, null);
    }

    @Override
    public void markCancelled(String runId) {
        runs.cancel(UUID.fromString(runId));
        // Sandbox calls still running for this run lose their egress credentials and are killed.
        executor.cancelRun(runId);
        // A remote A2A task is asked to cancel; whether it did is recorded with the run (slice 2.5).
        if (a2a != null) {
            a2a.cancelRemote(runId);
        }
    }

    @Override
    public void expireInput(String runId) {
        if (a2a != null) {
            a2a.cancelRemote(runId);
        }
        runs.fail(UUID.fromString(runId), "no reply to the remote agent's question in time; the remote task was asked to cancel", null);
    }

    @Override
    public String escalateApproval(String approvalId) {
        String status = runs.escalateApproval(UUID.fromString(approvalId));
        if ("PENDING".equals(status)) {
            try {
                notify.post("platform-approvals", "Approval " + approvalId + " has waited past its escalation time");
            } catch (RuntimeException e) {
                log.warn("Escalation notice for approval {} failed: {}", approvalId, e.toString());
            }
        }
        return status;
    }

    @Override
    public String expireApproval(String approvalId) {
        return runs.expireApproval(UUID.fromString(approvalId));
    }

    private String unavailable(Invocation run) {
        if (run.modelEnabled() == null) {
            return "no longer in the model catalog";
        }
        if (!run.modelEnabled()) {
            return "model disabled";
        }
        if (!"ACTIVE".equals(run.connectionStatus())) {
            return "connection " + run.connectionId() + " revoked";
        }
        if (run.connectionExpiresAt() != null && !run.connectionExpiresAt().toInstant().isAfter(clock.instant())) {
            return "connection " + run.connectionId() + " expired";
        }
        return null;
    }

    /** Models often wrap JSON in a single ```json fence; accept exactly that, nothing looser. */
    static String stripFence(String text) {
        String t = text.strip();
        if (t.startsWith("```") && t.endsWith("```") && t.length() >= 6) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                return t.substring(firstNewline + 1, t.length() - 3).strip();
            }
        }
        return t;
    }

    private static ApplicationFailure reject(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, NON_RETRYABLE);
    }
}
