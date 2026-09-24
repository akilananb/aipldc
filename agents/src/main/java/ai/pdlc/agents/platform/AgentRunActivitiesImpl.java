package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.core.platform.AgentInputs;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.OutputSchema;
import ai.pdlc.core.platform.ToolSpec;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Clock clock;

    @Autowired
    public AgentRunActivitiesImpl(RunStore runs, SecretsPort secrets, ModelInvoker models, ToolStore tools,
                                  ToolExecutor executor) {
        this(runs, secrets, models, tools, executor, Clock.systemUTC());
    }

    AgentRunActivitiesImpl(RunStore runs, SecretsPort secrets, ModelInvoker models, ToolStore tools,
                           ToolExecutor executor, Clock clock) {
        this.runs = runs;
        this.secrets = secrets;
        this.models = models;
        this.tools = tools;
        this.executor = executor;
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
        ModelInvoker.Reply reply = spec.toolsOrEmpty().isEmpty()
                ? callModel(runId, run, () -> models.call(endpoint, prompt, spec.limits().maxOutputTokens(),
                        Duration.ofSeconds(spec.limits().timeoutSeconds())))
                : toolLoop(run, spec, endpoint, prompt);
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

    /**
     * The bounded tool-use loop. Returns the model's final text reply with token usage summed over
     * all turns ({@code null} if any turn's usage was unreported).
     */
    private ModelInvoker.Reply toolLoop(Invocation run, AgentSpec spec, ModelInvoker.Endpoint endpoint, String prompt) {
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
        Instant deadline = clock.instant().plusSeconds(limits.timeoutSeconds());
        int attempt = run.attempts() + 1;
        List<ModelInvoker.Message> history = new ArrayList<>(List.of(new ModelInvoker.UserMessage(prompt)));
        Integer promptTokens = 0;
        Integer completionTokens = 0;
        int toolCalls = 0;
        for (int turn = 1; ; turn++) {
            if (turn > limits.modelTurns()) {
                throw loopLimit(run, "maxModelTurns=" + limits.modelTurns() + " reached without a final answer");
            }
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw loopLimit(run, "the run deadline of " + limits.timeoutSeconds() + "s passed");
            }
            ModelInvoker.Reply reply = callModel(runId, run,
                    () -> models.chat(endpoint, history, definitions, limits.maxOutputTokens(), remaining));
            promptTokens = sum(promptTokens, reply.promptTokens());
            completionTokens = sum(completionTokens, reply.completionTokens());
            if (reply.toolCalls().isEmpty()) {
                return new ModelInvoker.Reply(reply.text(), promptTokens, completionTokens);
            }
            if (toolCalls + reply.toolCalls().size() > limits.toolCalls()) {
                throw loopLimit(run, "maxToolCalls=" + limits.toolCalls() + " would be exceeded (" + toolCalls
                        + " made, " + reply.toolCalls().size() + " more requested)");
            }
            history.add(new ModelInvoker.AssistantMessage(reply.text(), reply.toolCalls()));
            List<ModelInvoker.ToolResult> results = new ArrayList<>();
            for (ModelInvoker.ToolCall call : reply.toolCalls()) {
                toolCalls++;
                ToolExecutor.Outcome outcome = executor.execute(
                        new ToolExecutor.Context(run.runId(), run.workspaceId(), attempt, turn, deadline), pinned, call);
                results.add(new ModelInvoker.ToolResult(call.id(), call.name(), outcome.content()));
            }
            history.add(new ModelInvoker.ToolResults(results));
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
