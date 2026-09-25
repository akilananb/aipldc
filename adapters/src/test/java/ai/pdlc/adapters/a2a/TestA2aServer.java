package ai.pdlc.adapters.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process remote A2A agent for tests (public: the agents runner tests reuse it), speaking the
 * JSON-RPC binding of A2A 1.0 or 0.3 ({@link #dialect}). Skills, chosen by {@code metadata.skill}:
 * <ul>
 *   <li>{@code echo} - completes at once with the text echoed as an artifact;</li>
 *   <li>{@code slow} - working until it has been polled {@link #slowPolls} times, then completed;</li>
 *   <li>{@code ask} - input-required with a question; a follow-up on the same task completes it;</li>
 *   <li>{@code fail} - failed with a status message;</li>
 *   <li>{@code hold} - working until cancelled (cancelable); {@code pinned} - working, not cancelable;</li>
 *   <li>{@code login} - auth-required.</li>
 * </ul>
 */
public final class TestA2aServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    public volatile A2aClient.Dialect dialect = A2aClient.Dialect.V1_0;
    public volatile boolean streaming = true;
    public volatile boolean legacyCardPathOnly;
    public volatile String cardEndpointOverride;
    public volatile String requiredBearer;
    public volatile int slowPolls = 2;
    public volatile List<String> skills = List.of("echo", "slow", "ask", "fail", "hold", "pinned", "login");
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final List<String> versions = new CopyOnWriteArrayList<>();
    public final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final Map<String, ObjectNode> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> skillOf = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> polls = new ConcurrentHashMap<>();
    private final Map<String, String> byMessageId = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final HttpServer server;

    public TestA2aServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/.well-known/agent-card.json", ex -> {
            if (legacyCardPathOnly) {
                respond(ex, 404, "{}");
            } else {
                respond(ex, 200, card());
            }
        });
        server.createContext("/.well-known/agent.json", ex -> respond(ex, 200, card()));
        server.createContext("/a2a", this::rpc);
        server.start();
    }

    public URI origin() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    public URI endpoint() {
        return URI.create(origin() + "/a2a");
    }

    /** Reports the task state as the server holds it. */
    public String state(String taskId) {
        ObjectNode t = tasks.get(taskId);
        return t == null ? null : t.path("status").path("state").asText();
    }

    private String card() {
        String url = cardEndpointOverride != null ? cardEndpointOverride : endpoint().toString();
        ObjectNode card = JSON.createObjectNode().put("name", "Test partner agent").put("description", "scripted").put("version", "1");
        card.putObject("capabilities").put("streaming", streaming);
        if (dialect == A2aClient.Dialect.V1_0) {
            card.putArray("supportedInterfaces").addObject().put("url", url).put("protocolBinding", "JSONRPC").put("protocolVersion", "1.0");
        } else {
            card.put("protocolVersion", "0.3.0").put("url", url).put("preferredTransport", "JSONRPC");
        }
        var list = card.putArray("skills");
        for (String s : skills) {
            list.addObject().put("id", s).put("name", s).put("description", "the " + s + " skill");
        }
        return card.toString();
    }

    private boolean v1() {
        return dialect == A2aClient.Dialect.V1_0;
    }

    private String wireState(String name) {
        return v1() ? "TASK_STATE_" + name.toUpperCase().replace('-', '_') : name;
    }

    private void rpc(HttpExchange ex) throws IOException {
        String authorization = ex.getRequestHeaders().getFirst("Authorization");
        authorizations.add(String.valueOf(authorization));
        versions.add(String.valueOf(ex.getRequestHeaders().getFirst("A2A-Version")));
        if (requiredBearer != null && !("Bearer " + requiredBearer).equals(authorization)) {
            respond(ex, 401, "{}");
            return;
        }
        JsonNode request = JSON.readTree(ex.getRequestBody().readAllBytes());
        String method = request.path("method").asText();
        JsonNode params = request.path("params");
        int id = request.path("id").asInt();
        switch (method) {
            case "SendMessage", "message/send" -> {
                calls.add("send " + params.path("message").path("messageId").asText() + " " + firstText(params));
                respond(ex, 200, result(id, wrapTask(onMessage(params))));
            }
            case "SendStreamingMessage", "message/stream" -> {
                calls.add("stream " + params.path("message").path("messageId").asText() + " " + firstText(params));
                stream(ex, id, params);
            }
            case "GetTask", "tasks/get" -> {
                String taskId = params.path("id").asText();
                calls.add("get " + taskId);
                ObjectNode task = tasks.get(taskId);
                if (task == null) {
                    respond(ex, 200, error(id, -32001, "task not found"));
                    return;
                }
                if ("slow".equals(skillOf.get(taskId)) && polls.get(taskId).incrementAndGet() >= slowPolls) {
                    complete(task, "slow result");
                }
                respond(ex, 200, result(id, wrapTask(task)));
            }
            case "CancelTask", "tasks/cancel" -> {
                String taskId = params.path("id").asText();
                calls.add("cancel " + taskId);
                ObjectNode task = tasks.get(taskId);
                if (task == null) {
                    respond(ex, 200, error(id, -32001, "task not found"));
                } else if ("pinned".equals(skillOf.get(taskId))) {
                    respond(ex, 200, error(id, -32002, "task cannot be canceled"));
                } else {
                    status(task, "canceled", null);
                    respond(ex, 200, result(id, wrapTask(task)));
                }
            }
            default -> respond(ex, 200, error(id, -32601, "Method not found"));
        }
    }

    private ObjectNode onMessage(JsonNode params) {
        JsonNode message = params.path("message");
        String existing = message.hasNonNull("taskId") ? message.get("taskId").asText() : null;
        String messageId = message.path("messageId").asText();
        String known = byMessageId.get(messageId);
        if (known != null) {
            return tasks.get(known); // idempotent on messageId
        }
        if (existing != null) {
            ObjectNode task = tasks.get(existing);
            byMessageId.put(messageId, existing);
            complete(task, "thanks: " + firstText(params));
            return task;
        }
        String skill = message.path("metadata").path("skill").asText("echo");
        String taskId = "task-" + ids.incrementAndGet();
        ObjectNode task = JSON.createObjectNode().put("id", taskId).put("contextId", "ctx-" + taskId);
        if (!v1()) {
            task.put("kind", "task");
        }
        tasks.put(taskId, task);
        skillOf.put(taskId, skill);
        polls.put(taskId, new AtomicInteger());
        byMessageId.put(messageId, taskId);
        task.putArray("artifacts");
        switch (skill) {
            case "echo" -> complete(task, "echo: " + firstText(params));
            case "ask" -> status(task, "input-required", "Which region should the report cover?");
            case "fail" -> status(task, "failed", "the partner system is down");
            case "login" -> status(task, "auth-required", "sign in to the partner system first");
            default -> status(task, "working", null);
        }
        return task;
    }

    private void stream(HttpExchange ex, int id, JsonNode params) throws IOException {
        ObjectNode task = onMessage(params);
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            ObjectNode working = task.deepCopy();
            ObjectNode workingStatus = working.putObject("status");
            workingStatus.put("state", wireState("working"));
            working.putArray("artifacts");
            event(out, id, v1() ? JSON.createObjectNode().set("task", working) : working);
            for (JsonNode artifact : task.path("artifacts")) {
                ObjectNode update = JSON.createObjectNode().put("taskId", task.path("id").asText())
                        .put("contextId", task.path("contextId").asText());
                update.set("artifact", artifact);
                if (!v1()) {
                    update.put("kind", "artifact-update");
                }
                event(out, id, v1() ? JSON.createObjectNode().set("artifactUpdate", update) : update);
            }
            ObjectNode statusUpdate = JSON.createObjectNode().put("taskId", task.path("id").asText())
                    .put("contextId", task.path("contextId").asText());
            statusUpdate.set("status", task.path("status"));
            if (!v1()) {
                statusUpdate.put("kind", "status-update").put("final", true);
            }
            event(out, id, v1() ? JSON.createObjectNode().set("statusUpdate", statusUpdate) : statusUpdate);
        }
    }

    private void event(OutputStream out, int id, JsonNode resultPayload) throws IOException {
        out.write(("data: " + result(id, resultPayload) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void complete(ObjectNode task, String text) {
        ObjectNode artifact = JSON.createObjectNode().put("artifactId", "a-" + task.path("id").asText()).put("name", "result");
        ObjectNode part = artifact.putArray("parts").addObject();
        if (!v1()) {
            part.put("kind", "text");
        }
        part.put("text", text);
        task.putArray("artifacts").add(artifact);
        status(task, "completed", null);
    }

    private void status(ObjectNode task, String name, String text) {
        ObjectNode status = task.putObject("status").put("state", wireState(name));
        if (text != null) {
            ObjectNode message = status.putObject("message").put("messageId", "m-" + ids.incrementAndGet())
                    .put("role", v1() ? "ROLE_AGENT" : "agent");
            ObjectNode part = message.putArray("parts").addObject();
            if (!v1()) {
                message.put("kind", "message");
                part.put("kind", "text");
            }
            part.put("text", text);
        }
    }

    private JsonNode wrapTask(ObjectNode task) {
        return v1() ? JSON.createObjectNode().set("task", task) : task;
    }

    private static String firstText(JsonNode params) {
        return params.path("message").path("parts").path(0).path("text").asText();
    }

    private static String result(int id, JsonNode result) {
        ObjectNode body = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id);
        body.set("result", result);
        return body.toString();
    }

    private static String error(int id, int code, String message) {
        ObjectNode body = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id);
        body.putObject("error").put("code", code).put("message", message);
        return body.toString();
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
