package ai.pdlc.agents.platform;

import ai.pdlc.adapters.mcp.McpAuth;
import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.agents.platform.RunStore.RemoteTask;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.platform.OutputSchema;
import ai.pdlc.core.workflow.AgentRunActivities;
import ai.pdlc.core.workflow.AgentRunWorkflow.AgentRunOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs a {@code rest} agent (docs/phase-2-execution-spec.md slice 2.6) against an existing HTTP
 * agent service through its declared {@link AgentSpec.RestBinding} - and nothing else: a job's
 * state is only what the declared {@code states} map says, and an unlisted value fails the run.
 *
 * <ul>
 *   <li><b>At call time</b> the connection must still be an active, unexpired {@code REST_AGENT}
 *       granted to the workspace; every URL passes the {@link EgressPolicy}; redirects are never
 *       followed; bodies are capped; the credential is redacted from anything stored.</li>
 *   <li><b>Submit identity.</b> The request {@code run:0} is recorded INTENDED → SENT → ACKED. A
 *       submit whose outcome is unknown (timeout, broken connection, 5xx) is resent only when the
 *       service honours {@code Idempotency-Key} ({@code idempotency: HEADER}, same key); otherwise
 *       the run fails as "outcome unknown" and nothing is resent.</li>
 *   <li><b>sync</b>: the 2xx response is the result. <b>async</b>: the job id is persisted the moment
 *       it is known, and a retry polls that job instead of submitting again.</li>
 *   <li><b>Cancellation</b> calls the declared cancel endpoint (2xx acknowledged, 400/409/422 refused,
 *       404 not found); without one it is recorded as unsupported - the request is abandoned, not undone.</li>
 * </ul>
 */
@Component
public class RestAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(RestAgentRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_BYTES = 1_000_000;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final String SYNC_DIALECT = "rest-sync";
    static final String ASYNC_DIALECT = "rest-async";

    private final RunStore runs;
    private final McpCredentials credentials;
    private final EgressPolicy egress;
    private final HttpClient http;
    private final Clock clock;
    private final Duration pollUnit;

    @Autowired
    public RestAgentRunner(RunStore runs, McpCredentials credentials, EgressPolicy egress) {
        this(runs, credentials, egress, Clock.systemUTC(), Duration.ofSeconds(1));
    }

    /** {@code pollUnit} is one "second" of {@code pollSeconds} (shortened in tests). */
    RestAgentRunner(RunStore runs, McpCredentials credentials, EgressPolicy egress, Clock clock, Duration pollUnit) {
        this.runs = runs;
        this.credentials = credentials;
        this.egress = egress;
        this.clock = clock;
        this.pollUnit = pollUnit;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** One HTTP exchange's result; {@code body} is null when it is not JSON. */
    private record Reply(int status, JsonNode body, String text) {
    }

    /** The exchange never completed: {@code maybeSent} when the request may have reached the service. */
    private static final class Unanswered extends Exception {
        final boolean maybeSent;

        Unanswered(String message, boolean maybeSent) {
            super(message);
            this.maybeSent = maybeSent;
        }
    }

    private record Service(URI base, McpAuth auth, String secret) {
    }

    AgentRunOutcome invoke(Invocation run, AgentSpec spec, String prompt) {
        UUID id = run.runId();
        Instant started = clock.instant();
        Instant deadline = started.plusMillis(spec.limits().timeoutSeconds() * 1000L - run.activeMs());
        try {
            Service service = connect(run);
            AgentSpec.RestBinding rest = spec.rest();
            boolean async = AgentSpec.RestBinding.ASYNC.equals(rest.mode());
            RemoteTask known = runs.remoteTask(id).orElse(null);
            if (async && known != null && known.taskId() != null) {
                log.info("Run {}: following existing job {} instead of submitting again", id, known.taskId());
                return follow(run, spec, service, known.taskId(), deadline);
            }
            Reply reply = submit(run, spec, service, prompt);
            if (!async) {
                return finish(run, spec, service, reply.body(), "COMPLETED", null);
            }
            JsonNode taskId = reply.body() == null ? null : reply.body().at(rest.taskIdPointer());
            if (taskId == null || taskId.isMissingNode() || taskId.isNull() || taskId.isContainerNode() || taskId.asText().isBlank()) {
                return failRun(id, "the service answered without a job id at " + rest.taskIdPointer());
            }
            runs.saveRemoteTask(id, ASYNC_DIALECT, taskId.asText(), null, "WORKING", null);
            log.info("Run {}: job {} submitted", id, taskId.asText());
            String state = mappedState(rest, reply.body());
            if (state != null && !"WORKING".equals(state)) {
                runs.saveRemoteTask(id, ASYNC_DIALECT, taskId.asText(), null, state, rawState(rest, reply.body()));
                return finish(run, spec, service, reply.body(), state, taskId.asText());
            }
            return follow(run, spec, service, taskId.asText(), deadline);
        } finally {
            runs.addActiveMs(id, Duration.between(started, clock.instant()).toMillis());
        }
    }

    /** Sends the submit request under the run's submit identity; returns only a 2xx reply. */
    private Reply submit(Invocation run, AgentSpec spec, Service service, String prompt) {
        UUID id = run.runId();
        AgentSpec.RestBinding rest = spec.rest();
        String messageId = id + ":0";
        String state = runs.intendSend(id, messageId);
        if (("SENT".equals(state) || "ACKED".equals(state)) && !rest.idempotentByHeader()) {
            return failRunAndReject(id, "outcome unknown: request " + messageId + " may have reached the service; "
                    + "it does not declare Idempotency-Key support, so it was not resent");
        }
        if (AgentSpec.RestBinding.SYNC.equals(rest.mode())) {
            runs.saveRemoteTask(id, SYNC_DIALECT, null, null, "WORKING", null);
        }
        runs.setSendState(id, messageId, "SENT");
        ObjectNode body = JSON.createObjectNode();
        body.set("inputs", JSON.valueToTree(run.inputs()));
        if (prompt != null) {
            body.put("prompt", prompt);
        }
        Reply reply;
        try {
            reply = exchange(service, rest.submit().method(), rest.submit().path(), null, body,
                    rest.idempotentByHeader() ? messageId : null);
        } catch (Unanswered e) {
            if (!e.maybeSent) {
                runs.setSendState(id, messageId, "INTENDED");
                throw retryable(e.getMessage());
            }
            return unknownOutcome(id, rest, messageId, e.getMessage());
        }
        if (reply.status() >= 500) {
            return unknownOutcome(id, rest, messageId, "the service answered HTTP " + reply.status());
        }
        runs.setSendState(id, messageId, "ACKED");
        if (reply.status() >= 300) {
            return failRunAndReject(id, reply.status() < 400 ? "the service answered " + reply.status() + "; redirects are not followed"
                    : "the service refused the request: " + errorText(rest, reply, service));
        }
        return reply;
    }

    /** The request may have been processed: resend (same key) only when the service dedups on it. */
    private Reply unknownOutcome(UUID id, AgentSpec.RestBinding rest, String messageId, String why) {
        if (rest.idempotentByHeader()) {
            throw retryable(why + "; resending " + messageId + " with the same Idempotency-Key");
        }
        return failRunAndReject(id, "outcome unknown: " + why + "; request " + messageId + " was not resent "
                + "(the service does not declare Idempotency-Key support)");
    }

    /** Polls the job's status until the declared mapping says it finished, or the agent's time runs out. */
    private AgentRunOutcome follow(Invocation run, AgentSpec spec, Service service, String taskId, Instant deadline) {
        UUID id = run.runId();
        AgentSpec.RestBinding rest = spec.rest();
        Duration wait = pollUnit.multipliedBy(rest.pollSecondsOrDefault());
        int failures = 0;
        while (true) {
            if (!clock.instant().plus(wait).isBefore(deadline)) {
                String ack = cancel(id, rest, service, taskId);
                return failRun(id, "the job did not finish within the agent's time; cancel: " + ack.toLowerCase().replace('_', ' '));
            }
            sleep(wait);
            String status = runs.load(id).map(Invocation::status).orElse("UNKNOWN");
            if (!"RUNNING".equals(status)) {
                throw reject("the run is " + status + "; stopped following job " + taskId);
            }
            Reply reply;
            try {
                reply = exchange(service, rest.status().method(), rest.status().path(), taskId, null, null);
            } catch (Unanswered e) {
                if (++failures >= 3) {
                    throw retryable("the job's status kept failing: " + e.getMessage());
                }
                continue;
            }
            if (reply.status() == 404) {
                return failRun(id, "the service no longer knows job " + taskId);
            }
            if (reply.status() >= 300 || reply.body() == null) {
                if (++failures >= 3) {
                    throw retryable("the job's status kept failing: HTTP " + reply.status());
                }
                continue;
            }
            failures = 0;
            String raw = rawState(rest, reply.body());
            String state = mappedState(rest, reply.body());
            if (state == null) {
                runs.saveRemoteTask(id, ASYNC_DIALECT, taskId, null, "UNKNOWN", raw);
                return failRun(id, "the service reported state " + (raw == null ? "(none)" : "'" + raw + "'")
                        + ", which the agent's mapping does not declare; it is not guessed");
            }
            runs.saveRemoteTask(id, ASYNC_DIALECT, taskId, null, state, raw);
            if (!"WORKING".equals(state)) {
                return finish(run, spec, service, reply.body(), state, taskId);
            }
        }
    }

    private AgentRunOutcome finish(Invocation run, AgentSpec spec, Service service, JsonNode body, String state, String taskId) {
        UUID id = run.runId();
        String runId = id.toString();
        AgentSpec.RestBinding rest = spec.rest();
        if (AgentSpec.RestBinding.SYNC.equals(rest.mode())) {
            runs.saveRemoteTask(id, SYNC_DIALECT, null, null, "COMPLETED", null);
        }
        switch (state) {
            case "COMPLETED" -> {
                JsonNode result = rest.resultPointer() == null ? body : body == null ? null : body.at(rest.resultPointer());
                if (result == null || result.isMissingNode()) {
                    return failRun(id, "the service's response has no result at " + rest.resultPointer());
                }
                String text = redact(result.isTextual() ? result.asText() : result.toString(), service.secret());
                String outputJson = null;
                if (spec.outputSchema() != null) {
                    JsonNode parsed = result;
                    if (result.isTextual()) {
                        try {
                            parsed = JSON.readTree(result.asText());
                        } catch (IOException e) {
                            parsed = null;
                        }
                    }
                    List<String> findings = parsed == null ? List.of("the service's result is not JSON")
                            : OutputSchema.validate(spec.outputSchema(), parsed);
                    if (!findings.isEmpty()) {
                        String error = "Service result does not match outputSchema: " + String.join("; ", findings);
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
            case "FAILED" -> {
                return failRun(id, "the service reported the job failed" + detail(rest, body, service));
            }
            case "CANCELED" -> {
                return failRun(id, "the service cancelled job " + taskId + detail(rest, body, service));
            }
            default -> throw retryable("unexpected state " + state);
        }
    }

    /** Best effort, and only for REST runs: asks the service to cancel the run's job and records what it did. */
    public void cancelRemote(String runId) {
        UUID id = UUID.fromString(runId);
        RemoteTask known = runs.remoteTask(id).orElse(null);
        if (known == null || known.dialect() == null || !known.dialect().startsWith("rest")) {
            return;
        }
        if (Set.of("COMPLETED", "FAILED", "CANCELED").contains(known.state())) {
            return;
        }
        if (SYNC_DIALECT.equals(known.dialect()) || known.taskId() == null) {
            runs.setRemoteCancel(id, "UNSUPPORTED");
            return;
        }
        Invocation run = runs.load(id).orElse(null);
        if (run == null) {
            return;
        }
        runs.setRemoteCancel(id, "REQUESTED");
        try {
            AgentSpec spec = ContentHash.read(run.specJson(), AgentSpec.class);
            cancel(id, spec.rest(), connect(run), known.taskId());
        } catch (RuntimeException e) {
            runs.setRemoteCancel(id, "FAILED");
            log.warn("Run {}: cancelling job {} failed: {}", id, known.taskId(), e.getMessage());
        }
    }

    private String cancel(UUID id, AgentSpec.RestBinding rest, Service service, String taskId) {
        String ack;
        if (rest.cancel() == null) {
            ack = "UNSUPPORTED";
        } else {
            try {
                int status = exchange(service, rest.cancel().method(), rest.cancel().path(), taskId, null, null).status();
                ack = status >= 200 && status < 300 ? "ACKNOWLEDGED"
                        : status == 404 ? "NOT_FOUND"
                        : status == 400 || status == 409 || status == 422 ? "REFUSED"
                        : "FAILED";
            } catch (Unanswered e) {
                ack = "FAILED";
            }
        }
        runs.setRemoteCancel(id, ack);
        log.info("Run {}: cancel of job {} -> {}", id, taskId, ack);
        return ack;
    }

    private Reply exchange(Service service, String method, String path, String taskId, JsonNode body, String idempotencyKey)
            throws Unanswered {
        URI uri = resolve(service.base(), path, taskId);
        EgressPolicy.Decision destination = egress.check(uri);
        if (!destination.allowed()) {
            throw reject("destination not allowed: " + destination.reason());
        }
        boolean effectful = !"GET".equals(method);
        for (int attempt = 1; ; attempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Accept", "application/json");
            String authorization = service.auth().header();
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            if (body != null) {
                request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<InputStream> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new Unanswered(method + " " + path + " failed (" + e.getClass().getSimpleName() + ")", effectful);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Unanswered("interrupted", effectful);
            }
            if (response.statusCode() == 401 && attempt == 1
                    && service.auth().onUnauthorized(response.headers().firstValue("WWW-Authenticate").orElse(""))) {
                close(response);
                continue;
            }
            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readNBytes(MAX_BYTES + 1);
            } catch (IOException e) {
                throw new Unanswered("the service's response could not be read", effectful);
            }
            if (bytes.length > MAX_BYTES) {
                throw reject("the service's response exceeded " + MAX_BYTES + " bytes");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            JsonNode json;
            try {
                json = text.isBlank() ? null : JSON.readTree(text);
            } catch (IOException e) {
                json = null;
            }
            return new Reply(response.statusCode(), json, text);
        }
    }

    static URI resolve(URI base, String path, String taskId) {
        String p = taskId == null ? path : path.replace("{taskId}", URLEncoder.encode(taskId, StandardCharsets.UTF_8).replace("+", "%20"));
        String b = base.toString();
        return URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + p);
    }

    private Service connect(Invocation run) {
        if (!"REST_AGENT".equals(run.connectionKind())) {
            throw reject("connection " + run.connectionId() + " is not a REST_AGENT connection");
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
        McpAuth auth;
        try {
            auth = credentials.auth(new McpCredentials.Connection(run.connectionId(), run.authType(), run.secretRef(), run.baseUrl(),
                    run.oauthClientId()));
        } catch (IllegalStateException e) {
            throw reject(e.getMessage());
        }
        return new Service(URI.create(run.baseUrl()), auth, McpCredentials.bearerValue(auth));
    }

    private static String rawState(AgentSpec.RestBinding rest, JsonNode body) {
        JsonNode node = body == null || rest.statePointer() == null ? null : body.at(rest.statePointer());
        return node == null || node.isMissingNode() || node.isNull() || node.isContainerNode() ? null : node.asText();
    }

    private static String mappedState(AgentSpec.RestBinding rest, JsonNode body) {
        String raw = rawState(rest, body);
        return raw == null ? null : rest.states().get(raw);
    }

    private String detail(AgentSpec.RestBinding rest, JsonNode body, Service service) {
        JsonNode error = rest.errorPointer() == null || body == null ? null : body.at(rest.errorPointer());
        return error == null || error.isMissingNode() || error.isNull() ? "" : ": " + redact(error.isTextual() ? error.asText() : error.toString(), service.secret());
    }

    private String errorText(AgentSpec.RestBinding rest, Reply reply, Service service) {
        String detail = detail(rest, reply.body(), service);
        return detail.isEmpty() ? "HTTP " + reply.status() : "HTTP " + reply.status() + detail;
    }

    private AgentRunOutcome failRun(UUID id, String error) {
        runs.fail(id, error, null);
        log.info("Run {}: {}", id, error);
        return new AgentRunOutcome(id.toString(), "FAILED", error);
    }

    private <T> T failRunAndReject(UUID id, String error) {
        runs.fail(id, error, null);
        throw reject(error);
    }

    private void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw retryable("interrupted");
        }
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    static String redact(String text, String secret) {
        return text == null || secret == null || secret.isEmpty() ? text : text.replace(secret, "[REDACTED]");
    }

    private static ApplicationFailure reject(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, AgentRunActivities.NON_RETRYABLE);
    }

    private static ApplicationFailure retryable(String message) {
        return ApplicationFailure.newFailure(message, "RemoteServiceError");
    }
}
