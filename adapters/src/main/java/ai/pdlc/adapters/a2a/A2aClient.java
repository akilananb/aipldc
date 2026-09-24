package ai.pdlc.adapters.a2a;

import ai.pdlc.adapters.mcp.McpAuth;
import ai.pdlc.core.platform.EgressPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A small client for remote A2A agents over the JSON-RPC binding (docs/phase-2-execution-spec.md
 * slice 2.5), speaking A2A 1.0 ({@code SendMessage}/{@code SendStreamingMessage}/{@code GetTask}/
 * {@code CancelTask}, ProtoJSON enums, {@code A2A-Version: 1.0}) or 0.3 ({@code message/send},
 * {@code message/stream}, {@code tasks/get}, {@code tasks/cancel}, {@code kind}-tagged objects) - the
 * dialect is chosen from the Agent Card, preferring 1.0.
 *
 * <p>The card is fetched through the egress guard and is <em>not</em> a trust credential: the only
 * things taken from it are the skill list, the protocol version and the JSON-RPC endpoint, and that
 * endpoint must have the same origin as the connection the enterprise approved. As for MCP: every
 * URL passes the guard, redirects are never followed, every exchange has a hard deadline (including a
 * stalled event stream) and a byte cap, and nothing reported contains a credential.
 */
public final class A2aClient {

    public enum Dialect { V1_0, V0_3 }

    /** Remote task states, normalised across dialects. */
    public enum State {
        SUBMITTED, WORKING, INPUT_REQUIRED, AUTH_REQUIRED, COMPLETED, FAILED, CANCELED, REJECTED, UNKNOWN;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
        }

        public boolean interrupted() {
            return this == INPUT_REQUIRED || this == AUTH_REQUIRED;
        }

        static State parse(String value) {
            String v = value == null ? "" : value.toUpperCase(Locale.ROOT).replace('-', '_');
            if (v.startsWith("TASK_STATE_")) {
                v = v.substring("TASK_STATE_".length());
            }
            if (v.equals("CANCELLED")) {
                v = "CANCELED";
            }
            try {
                return v.isEmpty() ? UNKNOWN : State.valueOf(v);
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    public record Skill(String id, String name, String description) {
    }

    /** What the platform takes from a card; {@code endpoint} has been checked to share the connection's origin. */
    public record Card(String name, URI endpoint, Dialect dialect, String tenant, boolean streaming, List<Skill> skills) {

        public boolean offers(String skillId) {
            return skills.stream().anyMatch(s -> s.id().equals(skillId));
        }

        public String version() {
            return dialect == Dialect.V1_0 ? "1.0" : "0.3";
        }
    }

    /** One output artifact: its text parts joined, and its first structured data part (if any). */
    public record Artifact(String id, String name, String text, JsonNode data) {
    }

    /**
     * A remote task as last seen. A reply that was a bare message (no task) is reported as a
     * completed task with a null id and the message as its only artifact.
     */
    public record Task(String id, String contextId, State state, String statusText, List<Artifact> artifacts) {

        public String outputText() {
            StringBuilder text = new StringBuilder();
            for (Artifact a : artifacts) {
                String part = a.text() != null && !a.text().isEmpty() ? a.text() : a.data() != null ? a.data().toString() : "";
                if (!part.isEmpty()) {
                    text.append(text.length() > 0 ? "\n" : "").append(part);
                }
            }
            return text.toString();
        }
    }

    /** How a cancel request ended: the agent acknowledged it, refused it (not cancelable), or cannot cancel at all. */
    public enum CancelAck { ACKNOWLEDGED, REFUSED, UNSUPPORTED, NOT_FOUND }

    public record CancelResult(CancelAck ack, Task task) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CARD_PATH = "/.well-known/agent-card.json";
    private static final String LEGACY_CARD_PATH = "/.well-known/agent.json";

    private final HttpClient http;
    private final Function<URI, EgressPolicy.Decision> guard;

    public A2aClient(Function<URI, EgressPolicy.Decision> guard) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build(),
                guard);
    }

    A2aClient(HttpClient http, Function<URI, EgressPolicy.Decision> guard) {
        this.http = http;
        this.guard = guard;
    }

    /**
     * Fetches and interprets the Agent Card at {@code origin} (the connection's base URL). Refuses a
     * card whose JSON-RPC endpoint is on another origin, or that offers neither 1.0 nor 0.3 JSON-RPC.
     */
    public Card card(URI origin, McpAuth auth, Duration timeout, int maxBytes) {
        JsonNode card;
        try {
            card = get(origin.resolve(CARD_PATH), auth, timeout, maxBytes);
        } catch (A2aException e) {
            if (e.code() != 404) {
                throw e;
            }
            card = get(origin.resolve(LEGACY_CARD_PATH), auth, timeout, maxBytes);
        }
        return interpret(origin, card);
    }

    static Card interpret(URI origin, JsonNode card) {
        URI endpoint = null;
        String tenant = null;
        Dialect dialect = null;
        for (String preferred : List.of("1.", "0.3")) {
            for (JsonNode i : card.path("supportedInterfaces")) {
                if ("JSONRPC".equalsIgnoreCase(i.path("protocolBinding").asText())
                        && i.path("protocolVersion").asText().startsWith(preferred) && i.hasNonNull("url")) {
                    endpoint = uri(i.get("url").asText());
                    tenant = i.hasNonNull("tenant") && !i.get("tenant").asText().isEmpty() ? i.get("tenant").asText() : null;
                    dialect = preferred.equals("1.") ? Dialect.V1_0 : Dialect.V0_3;
                    break;
                }
            }
            if (dialect != null) {
                break;
            }
        }
        if (dialect == null && card.hasNonNull("url")
                && "JSONRPC".equalsIgnoreCase(card.path("preferredTransport").asText("JSONRPC"))) {
            endpoint = uri(card.get("url").asText());
            dialect = Dialect.V0_3;
        }
        if (dialect == null) {
            for (JsonNode i : card.path("additionalInterfaces")) {
                if ("JSONRPC".equalsIgnoreCase(i.path("transport").asText()) && i.hasNonNull("url")) {
                    endpoint = uri(i.get("url").asText());
                    dialect = Dialect.V0_3;
                    break;
                }
            }
        }
        if (dialect == null || endpoint == null) {
            throw new A2aException("the agent card offers no JSON-RPC interface for A2A 1.0 or 0.3", false);
        }
        if (!sameOrigin(origin, endpoint)) {
            throw new A2aException("the agent card points its endpoint at " + endpoint.getScheme() + "://" + endpoint.getAuthority()
                    + ", not the approved origin " + origin.getScheme() + "://" + origin.getAuthority(), false);
        }
        List<Skill> skills = new ArrayList<>();
        for (JsonNode s : card.path("skills")) {
            if (s.hasNonNull("id")) {
                skills.add(new Skill(s.get("id").asText(), s.path("name").asText(s.get("id").asText()),
                        s.hasNonNull("description") ? s.get("description").asText() : null));
            }
        }
        return new Card(card.path("name").asText("A2A agent"), endpoint, dialect, tenant,
                card.path("capabilities").path("streaming").asBoolean(false), List.copyOf(skills));
    }

    /**
     * {@code skill} (nullable) is sent as the message's {@code metadata.skill}: A2A has no standard way
     * to address a skill, so it is a hint the remote agent may use; the card check is what the platform relies on.
     */
    public Session open(Card card, McpAuth auth, Duration timeout, int maxBytes, String skill) {
        return new Session(card, auth, timeout, maxBytes, skill);
    }

    /** JSON-RPC calls to one agent; not thread-safe. */
    public final class Session {
        private final Card card;
        private final McpAuth auth;
        private final Duration timeout;
        private final int maxBytes;
        private final String skill;
        private final AtomicInteger ids = new AtomicInteger();

        private Session(Card card, McpAuth auth, Duration timeout, int maxBytes, String skill) {
            this.card = card;
            this.auth = auth;
            this.timeout = timeout;
            this.maxBytes = maxBytes;
            this.skill = skill;
        }

        /**
         * Sends a user message without waiting for the task to finish ({@code returnImmediately} /
         * {@code blocking: false}). {@code taskId}/{@code contextId} continue an existing task.
         */
        public Task send(String messageId, String text, String taskId, String contextId) {
            JsonNode result = call(v1() ? "SendMessage" : "message/send", sendParams(messageId, text, taskId, contextId, true), true);
            return fromResponse(result);
        }

        /**
         * Sends a user message and follows the task over SSE until it is terminal or interrupted, or the
         * stream ends; each change is passed to {@code onUpdate}. Returns the last known task.
         */
        public Task stream(String messageId, String text, String taskId, String contextId, Duration deadline,
                           Consumer<Task> onUpdate) {
            ObjectNode body = envelope(v1() ? "SendStreamingMessage" : "message/stream",
                    sendParams(messageId, text, taskId, contextId, false));
            HttpResponse<InputStream> response = post(body, true, deadline);
            if (!response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
                return fromResponse(result(readBody(response, true), body));
            }
            InputStream in = response.body();
            CompletableFuture<Task> read = CompletableFuture.supplyAsync(() -> readStream(in, body, onUpdate));
            try {
                return read.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                close(response);
                throw new A2aException("the agent's event stream did not finish within " + deadline.toSeconds() + "s", true);
            } catch (ExecutionException e) {
                close(response);
                if (e.getCause() instanceof A2aException a) {
                    throw a;
                }
                throw new A2aException("the agent's event stream could not be read", true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close(response);
                throw new A2aException("interrupted", true);
            }
        }

        public Task get(String taskId) {
            ObjectNode params = tenant(JSON.createObjectNode().put("id", taskId));
            return fromResponse(call(v1() ? "GetTask" : "tasks/get", params, false));
        }

        /** Best effort: the agent decides. A refusal or missing support is reported, not thrown. */
        public CancelResult cancel(String taskId) {
            ObjectNode params = tenant(JSON.createObjectNode().put("id", taskId));
            try {
                Task task = fromResponse(call(v1() ? "CancelTask" : "tasks/cancel", params, true));
                return new CancelResult(task.state() == State.CANCELED ? CancelAck.ACKNOWLEDGED : CancelAck.REFUSED, task);
            } catch (A2aException e) {
                return switch (e.code()) {
                    case A2aException.TASK_NOT_CANCELABLE -> new CancelResult(CancelAck.REFUSED, null);
                    case A2aException.TASK_NOT_FOUND -> new CancelResult(CancelAck.NOT_FOUND, null);
                    case A2aException.UNSUPPORTED_OPERATION, A2aException.METHOD_NOT_FOUND -> new CancelResult(CancelAck.UNSUPPORTED, null);
                    default -> throw e;
                };
            }
        }

        private boolean v1() {
            return card.dialect() == Dialect.V1_0;
        }

        private ObjectNode tenant(ObjectNode params) {
            if (v1() && card.tenant() != null) {
                params.put("tenant", card.tenant());
            }
            return params;
        }

        private ObjectNode sendParams(String messageId, String text, String taskId, String contextId, boolean immediate) {
            ObjectNode message = JSON.createObjectNode().put("messageId", messageId).put("role", v1() ? "ROLE_USER" : "user");
            ObjectNode part = message.putArray("parts").addObject();
            if (!v1()) {
                message.put("kind", "message");
                part.put("kind", "text");
            }
            part.put("text", text);
            if (taskId != null) {
                message.put("taskId", taskId);
            }
            if (contextId != null) {
                message.put("contextId", contextId);
            }
            if (skill != null) {
                message.putObject("metadata").put("skill", skill);
            }
            ObjectNode params = tenant(JSON.createObjectNode());
            params.set("message", message);
            ObjectNode configuration = params.putObject("configuration");
            configuration.putArray("acceptedOutputModes").add("text/plain").add("application/json");
            if (immediate) {
                configuration.put(v1() ? "returnImmediately" : "blocking", v1());
            }
            return params;
        }

        private ObjectNode envelope(String method, JsonNode params) {
            ObjectNode body = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", ids.incrementAndGet()).put("method", method);
            body.set("params", params);
            return body;
        }

        private JsonNode call(String method, JsonNode params, boolean effectful) {
            ObjectNode body = envelope(method, params);
            return result(readBody(post(body, effectful, timeout), effectful), body);
        }

        private JsonNode result(JsonNode message, ObjectNode body) {
            if (message.has("error")) {
                JsonNode error = message.get("error");
                throw new A2aException(body.path("method").asText() + " failed: "
                        + error.path("message").asText("JSON-RPC error " + error.path("code").asInt()), error.path("code").asInt(), false);
            }
            if (!message.has("result")) {
                throw new A2aException(body.path("method").asText() + " returned neither a result nor an error", false);
            }
            return message.get("result");
        }

        private HttpResponse<InputStream> post(ObjectNode body, boolean effectful, Duration requestTimeout) {
            for (int attempt = 1; ; attempt++) {
                EgressPolicy.Decision destination = guard.apply(card.endpoint());
                if (!destination.allowed()) {
                    throw new A2aException("destination not allowed: " + destination.reason(), false);
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(card.endpoint()).timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("A2A-Version", card.version())
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
                String authorization = auth.header();
                if (authorization != null) {
                    request.header("Authorization", authorization);
                }
                HttpResponse<InputStream> response = exchange(request.build(), body.path("method").asText(), effectful, requestTimeout);
                int status = response.statusCode();
                if (status == 401 && attempt == 1) {
                    close(response);
                    if (auth.onUnauthorized(response.headers().firstValue("WWW-Authenticate").orElse(""))) {
                        continue;
                    }
                    throw new A2aException("the agent rejected the connection's credentials (401)", false);
                }
                if (status >= 300 && status < 400) {
                    close(response);
                    throw new A2aException("the agent answered " + status + "; redirects are not followed", false);
                }
                if (status >= 400) {
                    close(response);
                    throw new A2aException(body.path("method").asText() + " failed: HTTP " + status, status >= 500 && effectful);
                }
                return response;
            }
        }

        private JsonNode readBody(HttpResponse<InputStream> response, boolean effectful) {
            InputStream in = response.body();
            CompletableFuture<JsonNode> read = CompletableFuture.supplyAsync(() -> readJson(in, maxBytes, "response"));
            try {
                return read.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                close(response);
                throw new A2aException("the agent's response did not complete within " + timeout.toSeconds() + "s", effectful);
            } catch (ExecutionException e) {
                close(response);
                if (e.getCause() instanceof A2aException a) {
                    throw a;
                }
                throw new A2aException("the agent's response could not be read", effectful);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close(response);
                throw new A2aException("interrupted", effectful);
            }
        }

        private Task readStream(InputStream in, ObjectNode body, Consumer<Task> onUpdate) {
            long read = 0;
            Task current = null;
            Map<String, Artifact> artifacts = new LinkedHashMap<>();
            try (in; BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = lines.readLine()) != null) {
                    read += line.length() + 1;
                    if (read > maxBytes) {
                        throw new A2aException("the agent's event stream exceeded " + maxBytes + " bytes", true);
                    }
                    if (line.startsWith("data:")) {
                        data.append(line.substring(5).stripLeading());
                        continue;
                    }
                    if (!line.isEmpty() || data.length() == 0) {
                        continue;
                    }
                    JsonNode event = result(JSON.readTree(data.toString()), body);
                    data.setLength(0);
                    Update update = update(event, current, artifacts);
                    current = update.task();
                    if (current != null) {
                        onUpdate.accept(current);
                    }
                    if (update.last() || current != null && (current.state().terminal() || current.state().interrupted())) {
                        return current;
                    }
                }
                if (current == null) {
                    throw new A2aException("the agent closed the event stream before reporting a task", true);
                }
                return current;
            } catch (IOException e) {
                throw new A2aException("the agent's event stream could not be read", true);
            }
        }

        private record Update(Task task, boolean last) {
        }

        /** Folds one stream event (1.0 wrapper or 0.3 {@code kind}) into the task seen so far. */
        private Update update(JsonNode event, Task current, Map<String, Artifact> artifacts) {
            String kind = v1() ? event.has("task") ? "task" : event.has("message") ? "message"
                    : event.has("statusUpdate") ? "status-update" : event.has("artifactUpdate") ? "artifact-update" : ""
                    : event.path("kind").asText();
            JsonNode payload = v1() ? event.path(switch (kind) {
                case "status-update" -> "statusUpdate";
                case "artifact-update" -> "artifactUpdate";
                default -> kind;
            }) : event;
            switch (kind) {
                case "task" -> {
                    Task task = task(payload);
                    artifacts.clear();
                    task.artifacts().forEach(a -> artifacts.put(a.id(), a));
                    return new Update(task, false);
                }
                case "message" -> {
                    return new Update(messageTask(payload), true);
                }
                case "status-update" -> {
                    Task base = current != null ? current : new Task(payload.path("taskId").asText(null),
                            payload.path("contextId").asText(null), State.UNKNOWN, null, List.of());
                    JsonNode status = payload.path("status");
                    return new Update(new Task(base.id(), base.contextId(), State.parse(status.path("state").asText()),
                            text(status.path("message").path("parts")), List.copyOf(artifacts.values())),
                            payload.path("final").asBoolean(false));
                }
                case "artifact-update" -> {
                    Artifact incoming = artifact(payload.path("artifact"));
                    Artifact existing = artifacts.get(incoming.id());
                    artifacts.put(incoming.id(), payload.path("append").asBoolean(false) && existing != null
                            ? new Artifact(existing.id(), existing.name(), existing.text() + incoming.text(),
                                    incoming.data() != null ? incoming.data() : existing.data())
                            : incoming);
                    Task base = current != null ? current : new Task(payload.path("taskId").asText(null),
                            payload.path("contextId").asText(null), State.WORKING, null, List.of());
                    return new Update(new Task(base.id(), base.contextId(), base.state(), base.statusText(),
                            List.copyOf(artifacts.values())), false);
                }
                default -> {
                    return new Update(current, false);
                }
            }
        }

        /** {@code SendMessage}/{@code GetTask}/{@code CancelTask} result in either dialect. */
        private Task fromResponse(JsonNode result) {
            if (v1()) {
                if (result.has("message") && !result.has("task") && !result.has("id")) {
                    return messageTask(result.get("message"));
                }
                return task(result.has("task") ? result.get("task") : result);
            }
            return "message".equals(result.path("kind").asText()) ? messageTask(result) : task(result);
        }

        private Task task(JsonNode t) {
            if (!t.hasNonNull("id")) {
                throw new A2aException("the agent returned a task without an id", false);
            }
            List<Artifact> list = new ArrayList<>();
            for (JsonNode a : t.path("artifacts")) {
                list.add(artifact(a));
            }
            JsonNode status = t.path("status");
            return new Task(t.get("id").asText(), t.hasNonNull("contextId") ? t.get("contextId").asText() : null,
                    State.parse(status.path("state").asText()), text(status.path("message").path("parts")), List.copyOf(list));
        }

        private Task messageTask(JsonNode message) {
            return new Task(message.hasNonNull("taskId") ? message.get("taskId").asText() : null,
                    message.hasNonNull("contextId") ? message.get("contextId").asText() : null, State.COMPLETED, null,
                    List.of(new Artifact(message.path("messageId").asText("message"), null, text(message.path("parts")),
                            data(message.path("parts")))));
        }
    }

    private static Artifact artifact(JsonNode a) {
        return new Artifact(a.path("artifactId").asText(""), a.hasNonNull("name") ? a.get("name").asText() : null,
                text(a.path("parts")), data(a.path("parts")));
    }

    /** Text parts joined with newlines; file parts are named, never fetched. */
    private static String text(JsonNode parts) {
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode p : parts) {
            String piece = p.has("text") ? p.get("text").asText()
                    : p.has("data") ? null
                    : p.has("url") || p.has("raw") || p.has("file") ? "[file " + p.path("filename").asText(p.path("file").path("name").asText("omitted")) + "]"
                    : null;
            if (piece != null) {
                text.append(text.length() > 0 ? "\n" : "").append(piece);
            }
        }
        return text.toString();
    }

    private static JsonNode data(JsonNode parts) {
        if (parts != null && parts.isArray()) {
            for (JsonNode p : parts) {
                if (p.has("data")) {
                    return p.get("data");
                }
            }
        }
        return null;
    }

    private JsonNode get(URI uri, McpAuth auth, Duration timeout, int maxBytes) {
        EgressPolicy.Decision destination = guard.apply(uri);
        if (!destination.allowed()) {
            throw new A2aException("destination not allowed: " + destination.reason(), false);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET();
        String authorization = auth.header();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<InputStream> response = exchange(request.build(), "agent card", false, timeout);
        int status = response.statusCode();
        if (status != 200) {
            close(response);
            throw new A2aException("the agent card request answered " + status
                    + (status >= 300 && status < 400 ? "; redirects are not followed" : ""), status, false);
        }
        InputStream in = response.body();
        try {
            return CompletableFuture.supplyAsync(() -> readJson(in, maxBytes, "agent card")).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            close(response);
            throw new A2aException("the agent card did not arrive within " + timeout.toSeconds() + "s", false);
        } catch (ExecutionException e) {
            close(response);
            throw e.getCause() instanceof A2aException a ? a : new A2aException("the agent card could not be read", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(response);
            throw new A2aException("interrupted", false);
        }
    }

    private HttpResponse<InputStream> exchange(HttpRequest request, String what, boolean effectful, Duration timeout) {
        try {
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            String how = cause instanceof HttpTimeoutException || cause instanceof TimeoutException
                    ? "timed out after " + timeout.toSeconds() + "s" : "failed (" + cause.getClass().getSimpleName() + ")";
            throw new A2aException(what + " " + how, effectful);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2aException(what + " interrupted", effectful);
        }
    }

    private static JsonNode readJson(InputStream in, int maxBytes, String what) {
        try (in) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new A2aException("the agent's " + what + " exceeded " + maxBytes + " bytes", false);
            }
            return JSON.readTree(bytes);
        } catch (IOException e) {
            throw new A2aException("the agent's " + what + " is not valid JSON", false);
        }
    }

    private static URI uri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new A2aException("the agent card has an invalid URL", false);
        }
    }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URI u) {
        return u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
