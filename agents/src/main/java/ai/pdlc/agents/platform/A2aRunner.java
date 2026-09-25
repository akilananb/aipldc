package ai.pdlc.agents.platform;

import ai.pdlc.adapters.a2a.A2aClient;
import ai.pdlc.adapters.a2a.A2aException;
import ai.pdlc.adapters.mcp.McpAuth;
import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.agents.platform.RunStore.RemoteTask;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.platform.OutputSchema;
import ai.pdlc.core.workflow.AgentRunActivities;
import ai.pdlc.core.workflow.AgentRunWorkflow;
import ai.pdlc.core.workflow.AgentRunWorkflow.AgentRunOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Runs an {@code a2a} agent (docs/phase-2-execution-spec.md slice 2.5): delegates the rendered
 * prompt to a remote A2A agent and follows its task, with honest status semantics.
 *
 * <ul>
 *   <li><b>At call time</b> the connection must still be an active, unexpired {@code A2A_AGENT}
 *       granted to the workspace, and the agent's card - re-read every invocation, never trusted for
 *       more than its skill list, version and same-origin endpoint - must still offer the pinned skill.</li>
 *   <li><b>Invocation identity.</b> Each message has a stable id ({@code run:0} for the prompt,
 *       {@code run:<seq>} for an operator's reply) recorded INTENDED → SENT → ACKED. The remote task
 *       id is persisted the moment it is known. A retry that finds a message SENT but unanswered
 *       reconciles through {@code GetTask} instead of sending again; when there is no task to ask
 *       about, the run fails as "outcome unknown" rather than risk a duplicate.</li>
 *   <li><b>States.</b> completed → SUCCEEDED (artifacts are the output, checked against
 *       {@code outputSchema}); failed/rejected → FAILED with the remote message; canceled →
 *       CANCELLED; input-required → AWAITING_INPUT with the question; auth-required →
 *       AWAITING_AUTH. Running out of the agent's time asks the remote task to cancel and fails.</li>
 *   <li><b>Cancellation</b> is best effort: {@link #cancelRemote} asks the remote agent and records
 *       whether it acknowledged, refused or cannot cancel.</li>
 * </ul>
 * Credentials never leave the worker: the bearer or OAuth token is redacted from anything stored.
 */
@Component
public class A2aRunner {

    private static final Logger log = LoggerFactory.getLogger(A2aRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int CARD_BYTES = 256_000;
    static final int RESPONSE_BYTES = 1_000_000;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final RunStore runs;
    private final McpCredentials credentials;
    private final A2aClient client;
    private final Clock clock;
    private final Duration pollMin;
    private final Duration pollMax;

    @Autowired
    public A2aRunner(RunStore runs, McpCredentials credentials, EgressPolicy egress) {
        this(runs, credentials, new A2aClient(egress::check), Clock.systemUTC(), Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    A2aRunner(RunStore runs, McpCredentials credentials, A2aClient client, Clock clock, Duration pollMin, Duration pollMax) {
        this.runs = runs;
        this.credentials = credentials;
        this.client = client;
        this.clock = clock;
        this.pollMin = pollMin;
        this.pollMax = pollMax;
    }

    /** The connected agent, re-read for this invocation. */
    private record Remote(A2aClient.Session session, A2aClient.Card card, String secret) {
    }

    AgentRunOutcome invoke(Invocation run, AgentSpec spec, String prompt) {
        UUID id = run.runId();
        String runId = id.toString();
        Instant started = clock.instant();
        Instant deadline = started.plusMillis(spec.limits().timeoutSeconds() * 1000L - run.activeMs());
        try {
            Remote remote = connect(run, spec.remote().skill());
            RemoteTask known = runs.remoteTask(id).orElse(null);
            A2aClient.Task task = advance(run, remote, known, prompt, deadline);
            task = follow(id, remote, task, deadline);
            record(id, remote, task); // the state the run is decided on is the state on record
            return finish(run, spec, remote, task);
        } finally {
            runs.addActiveMs(id, Duration.between(started, clock.instant()).toMillis());
        }
    }

    /** Sends whatever this invocation owes the remote agent (the prompt, or a reply), or reconciles a send that may have happened. */
    private A2aClient.Task advance(Invocation run, Remote remote, RemoteTask known, String prompt, Instant deadline) {
        UUID id = run.runId();
        if (known == null || known.taskId() == null) {
            return send(run, remote, id + ":0", prompt, null, null, deadline);
        }
        A2aClient.State state = A2aClient.State.valueOf(known.state());
        if (state == A2aClient.State.INPUT_REQUIRED) {
            RunStore.StoredMessage reply = lastReply(id);
            if (reply == null) {
                return current(remote, known);
            }
            String text;
            try {
                text = JSON.readTree(reply.contentJson()).path("text").asText();
            } catch (java.io.IOException e) {
                throw reject("the stored reply " + reply.seq() + " is unreadable");
            }
            return send(run, remote, id + ":" + reply.seq(), text, known.taskId(), known.contextId(), deadline);
        }
        return current(remote, known);
    }

    private A2aClient.Task current(Remote remote, RemoteTask known) {
        try {
            return remote.session().get(known.taskId());
        } catch (A2aException e) {
            throw retryable("GetTask for remote task " + known.taskId() + " failed: " + redact(e.getMessage(), remote.secret()));
        }
    }

    private A2aClient.Task send(Invocation run, Remote remote, String messageId, String text, String taskId, String contextId,
                                Instant deadline) {
        UUID id = run.runId();
        String state = runs.intendSend(id, messageId);
        if ("ACKED".equals(state) || "SENT".equals(state)) {
            if (taskId != null) {
                A2aClient.Task task = current(remote, runs.remoteTask(id).orElseThrow());
                if ("ACKED".equals(state) || task.state() != A2aClient.State.INPUT_REQUIRED) {
                    runs.setSendState(id, messageId, "ACKED");
                    return task; // the reply arrived: the task moved on
                }
                // still waiting for input: our reply never arrived, so sending it again (same id) is safe
            } else if ("SENT".equals(state)) {
                runs.fail(id, "outcome unknown: the remote agent may have received message " + messageId
                        + " but never answered with a task; it was not resent", null);
                throw reject("outcome of message " + messageId + " is unknown; not resent");
            }
        }
        runs.setSendState(id, messageId, "SENT");
        A2aClient.Task task;
        try {
            if (remote.card().streaming()) {
                task = remote.session().stream(messageId, text, taskId, contextId, remaining(deadline),
                        t -> record(id, remote, t));
            } else {
                task = remote.session().send(messageId, text, taskId, contextId);
            }
        } catch (A2aException e) {
            if (!e.maybeSent()) {
                runs.setSendState(id, messageId, "INTENDED");
            }
            throw retryable("sending message " + messageId + " failed: " + redact(e.getMessage(), remote.secret()));
        }
        runs.setSendState(id, messageId, "ACKED");
        record(id, remote, task);
        log.info("Run {}: message {} sent to {} ({}), remote task {} is {}", id, messageId, remote.card().name(),
                remote.card().version(), task.id(), task.state());
        return task;
    }

    /** Polls until the task is terminal or interrupted, or the agent's time runs out. */
    private A2aClient.Task follow(UUID id, Remote remote, A2aClient.Task task, Instant deadline) {
        Duration wait = pollMin;
        int failures = 0;
        while (!task.state().terminal() && !task.state().interrupted()) {
            if (task.id() == null) {
                throw reject("the remote agent answered without a task to follow");
            }
            if (!clock.instant().plus(wait).isBefore(deadline)) {
                A2aClient.CancelResult cancel = cancel(id, remote, task.id());
                String error = "the remote task did not finish within the agent's time; it was asked to cancel ("
                        + cancel.ack().name().toLowerCase() + ")";
                runs.fail(id, error, null);
                throw reject(error);
            }
            sleep(wait);
            String status = runs.load(id).map(Invocation::status).orElse("UNKNOWN");
            if (!"RUNNING".equals(status)) {
                // Cancelled (or otherwise ended) while we were following: stop calling the remote agent.
                throw reject("the run is " + status + "; stopped following remote task " + task.id());
            }
            try {
                A2aClient.Task next = remote.session().get(task.id());
                failures = 0;
                if (next.state() != task.state() || !next.artifacts().equals(task.artifacts())) {
                    record(id, remote, next);
                }
                task = next;
            } catch (A2aException e) {
                if (++failures >= 3) {
                    throw retryable("GetTask kept failing: " + redact(e.getMessage(), remote.secret()));
                }
            }
            wait = wait.multipliedBy(2).compareTo(pollMax) > 0 ? pollMax : wait.multipliedBy(2);
        }
        return task;
    }

    private AgentRunOutcome finish(Invocation run, AgentSpec spec, Remote remote, A2aClient.Task task) {
        UUID id = run.runId();
        String runId = id.toString();
        String statusText = redact(task.statusText(), remote.secret());
        switch (task.state()) {
            case COMPLETED -> {
                String text = redact(task.outputText(), remote.secret());
                String outputJson = null;
                if (spec.outputSchema() != null) {
                    JsonNode parsed = structured(task);
                    List<String> findings = parsed == null ? List.of("the remote agent's output is not JSON")
                            : OutputSchema.validate(spec.outputSchema(), parsed);
                    if (!findings.isEmpty()) {
                        String error = "Remote output does not match outputSchema: " + String.join("; ", findings);
                        runs.fail(id, error, text);
                        throw reject(error);
                    }
                    outputJson = parsed.toString();
                }
                if (!runs.complete(id, text, outputJson, null, null)) {
                    return new AgentRunOutcome(runId, runs.load(id).map(Invocation::status).orElse("UNKNOWN"), null);
                }
                return new AgentRunOutcome(runId, "SUCCEEDED", null);
            }
            case FAILED, REJECTED -> {
                String error = "the remote agent reported " + task.state().name().toLowerCase()
                        + (statusText == null || statusText.isBlank() ? "" : ": " + statusText);
                runs.fail(id, error, redact(task.outputText(), remote.secret()));
                return new AgentRunOutcome(runId, "FAILED", error);
            }
            case CANCELED -> {
                runs.fail(id, "the remote agent cancelled its task" + (statusText == null ? "" : ": " + statusText), null);
                return new AgentRunOutcome(runId, "FAILED", "the remote agent cancelled its task");
            }
            case INPUT_REQUIRED, AUTH_REQUIRED -> {
                String status = task.state() == A2aClient.State.INPUT_REQUIRED ? AgentRunWorkflow.AWAITING_INPUT : AgentRunWorkflow.AWAITING_AUTH;
                appendQuestion(id, statusText);
                if (!runs.pause(id, status)) {
                    return new AgentRunOutcome(runId, runs.load(id).map(Invocation::status).orElse("UNKNOWN"), null);
                }
                log.info("Run {}: remote task {} is {}; waiting", id, task.id(), task.state());
                return new AgentRunOutcome(runId, status, null);
            }
            default -> throw retryable("the remote task is in an unexpected state " + task.state());
        }
    }

    /** Best effort: asks the remote agent to cancel the run's task and records what it did. */
    public void cancelRemote(String runId) {
        UUID id = UUID.fromString(runId);
        RemoteTask known = runs.remoteTask(id).orElse(null);
        if (known == null || known.taskId() == null || !("1.0".equals(known.dialect()) || "0.3".equals(known.dialect()))
                || A2aClient.State.valueOf(known.state()).terminal()) {
            return;
        }
        Invocation run = runs.load(id).orElse(null);
        if (run == null) {
            return;
        }
        runs.setRemoteCancel(id, "REQUESTED");
        try {
            AgentSpec spec = ai.pdlc.core.platform.ContentHash.read(run.specJson(), AgentSpec.class);
            Remote remote = connect(run, spec.remote().skill());
            cancel(id, remote, known.taskId());
        } catch (RuntimeException e) {
            runs.setRemoteCancel(id, "FAILED");
            log.warn("Run {}: asking the remote agent to cancel task {} failed: {}", id, known.taskId(), e.getMessage());
        }
    }

    private A2aClient.CancelResult cancel(UUID id, Remote remote, String taskId) {
        A2aClient.CancelResult result;
        try {
            result = remote.session().cancel(taskId);
        } catch (A2aException e) {
            runs.setRemoteCancel(id, "FAILED");
            log.warn("Run {}: CancelTask {} failed: {}", id, taskId, redact(e.getMessage(), remote.secret()));
            return new A2aClient.CancelResult(A2aClient.CancelAck.UNSUPPORTED, null);
        }
        if (result.task() != null) {
            record(id, remote, result.task());
        }
        runs.setRemoteCancel(id, result.ack().name());
        log.info("Run {}: remote agent {} cancel of task {}", id, result.ack().name().toLowerCase(), taskId);
        return result;
    }

    /** Re-checks the connection and the card, then opens a session with the connection's credential. */
    private Remote connect(Invocation run, String skill) {
        if (!"A2A_AGENT".equals(run.connectionKind())) {
            throw reject("connection " + run.connectionId() + " is not an A2A_AGENT connection");
        }
        if (!"ACTIVE".equals(run.connectionStatus())) {
            throw reject("connection " + run.connectionId() + " is revoked");
        }
        if (run.connectionExpiresAt() != null && !run.connectionExpiresAt().toInstant().isAfter(clock.instant())) {
            throw reject("connection " + run.connectionId() + " expired");
        }
        if (!run.granted()) {
            throw reject("connection " + run.connectionId() + " is not granted to workspace " + run.workspaceId());
        }
        URI origin = URI.create(run.baseUrl());
        McpAuth cardAuth;
        try {
            // Cards are public by design; a bearer connection sends its key, OAuth waits for the endpoint.
            cardAuth = "API_KEY".equals(run.authType()) ? credentials.auth(connection(run, run.baseUrl())) : McpAuth.NONE;
        } catch (IllegalStateException e) {
            throw reject(e.getMessage());
        }
        A2aClient.Card card;
        try {
            card = client.card(origin, cardAuth, REQUEST_TIMEOUT, CARD_BYTES);
        } catch (A2aException e) {
            throw retryable("the agent card could not be read: " + e.getMessage());
        }
        if (!card.offers(skill)) {
            throw reject("the remote agent's card no longer offers skill " + skill);
        }
        McpAuth auth;
        try {
            auth = credentials.auth(connection(run, card.endpoint().toString()));
        } catch (IllegalStateException e) {
            throw reject(e.getMessage());
        }
        return new Remote(client.open(card, auth, REQUEST_TIMEOUT, RESPONSE_BYTES, skill), card, McpCredentials.bearerValue(auth));
    }

    private static McpCredentials.Connection connection(Invocation run, String url) {
        return new McpCredentials.Connection(run.connectionId(), run.authType(), run.secretRef(), url, run.oauthClientId());
    }

    private void record(UUID id, Remote remote, A2aClient.Task task) {
        if (task.id() != null) {
            runs.saveRemoteTask(id, remote.card().version(), task.id(), task.contextId(), task.state().name(),
                    redact(task.statusText(), remote.secret()));
        }
    }

    private RunStore.StoredMessage lastReply(UUID id) {
        RunStore.StoredMessage last = null;
        for (RunStore.StoredMessage m : runs.messages(id)) {
            if ("REMOTE_USER".equals(m.kind())) {
                last = m;
            }
        }
        return last;
    }

    private void appendQuestion(UUID id, String question) {
        List<RunStore.StoredMessage> messages = runs.messages(id);
        int seq = messages.isEmpty() ? 1 : messages.get(messages.size() - 1).seq() + 1;
        runs.appendMessage(id, seq, "REMOTE_AGENT", JSON.createObjectNode().put("text", question == null ? "" : question).toString());
    }

    /** A JSON output: the first data part, else the text parsed as JSON; null when neither is JSON. */
    private static JsonNode structured(A2aClient.Task task) {
        for (A2aClient.Artifact a : task.artifacts()) {
            if (a.data() != null) {
                return a.data();
            }
        }
        try {
            return JSON.readTree(task.outputText());
        } catch (Exception e) {
            return null;
        }
    }

    private Duration remaining(Instant deadline) {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.isNegative() ? Duration.ofSeconds(1) : left;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw retryable("interrupted");
        }
    }

    static String redact(String text, String secret) {
        return text == null || secret == null || secret.isEmpty() ? text : text.replace(secret, "[REDACTED]");
    }

    private static ApplicationFailure reject(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, AgentRunActivities.NON_RETRYABLE);
    }

    private static ApplicationFailure retryable(String message) {
        return ApplicationFailure.newFailure(message, "RemoteAgentError");
    }
}
