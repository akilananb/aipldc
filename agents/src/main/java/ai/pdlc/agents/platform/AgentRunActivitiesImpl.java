package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.core.platform.AgentInputs;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.OutputSchema;
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
import java.util.List;
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
    private final Clock clock;

    @Autowired
    public AgentRunActivitiesImpl(RunStore runs, SecretsPort secrets, ModelInvoker models) {
        this(runs, secrets, models, Clock.systemUTC());
    }

    AgentRunActivitiesImpl(RunStore runs, SecretsPort secrets, ModelInvoker models, Clock clock) {
        this.runs = runs;
        this.secrets = secrets;
        this.models = models;
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
        ModelInvoker.Reply reply;
        try {
            reply = models.call(new ModelInvoker.Endpoint(run.baseUrl(), apiKey, run.providerModel()), prompt,
                    spec.limits().maxOutputTokens(), Duration.ofSeconds(spec.limits().timeoutSeconds()));
        } catch (RuntimeException e) {
            log.warn("Run {}: model call to {} failed: {}", runId, run.providerModel(), e.toString());
            throw ApplicationFailure.newFailure("Model call to " + run.providerModel() + " failed: " + e.getMessage(),
                    "ProviderError");
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
