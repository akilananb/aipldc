package ai.pdlc.adapters.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process Streamable-HTTP MCP server plus an OAuth authorization server, for tests
 * (public: the agents executor tests reuse it). Behaviour is switched with the public fields.
 */
public final class TestMcpServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    public volatile boolean sse;
    public volatile boolean requireOAuth;
    public volatile String requiredBearer;
    public volatile String lookupDescription = "Look up an order by id";
    public volatile boolean offerCancel = true;
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final List<String> authorizations = new CopyOnWriteArrayList<>();
    public final AtomicInteger tokenRequests = new AtomicInteger();
    private final HttpServer server;

    public TestMcpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/mcp", this::mcp);
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/.well-known/oauth-protected-resource/mcp", ex -> json(ex, 200,
                "{\"resource\":\"" + endpoint() + "\",\"authorization_servers\":[\"" + base() + "/as\"]}"));
        server.createContext("/.well-known/oauth-authorization-server/as", ex -> json(ex, 200,
                "{\"issuer\":\"" + base() + "/as\",\"token_endpoint\":\"" + base() + "/as/token\","
                        + "\"grant_types_supported\":[\"client_credentials\"]}"));
        server.createContext("/as/token", this::token);
        server.start();
    }

    public String base() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public URI endpoint() {
        return URI.create(base() + "/mcp");
    }

    private void token(HttpExchange ex) throws IOException {
        tokenRequests.incrementAndGet();
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String basic = "Basic " + Base64.getEncoder().encodeToString("platform-client:cs-secret-42".getBytes(StandardCharsets.UTF_8));
        if (!basic.equals(ex.getRequestHeaders().getFirst("Authorization"))
                || !form.contains("grant_type=client_credentials") || !form.contains("resource=")) {
            json(ex, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        json(ex, 200, "{\"access_token\":\"at-777\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
    }

    private void mcp(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizations.add(String.valueOf(auth));
        if (requireOAuth && !"Bearer at-777".equals(auth)) {
            ex.getResponseHeaders().add("WWW-Authenticate",
                    "Bearer resource_metadata=\"" + base() + "/.well-known/oauth-protected-resource/mcp\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (requiredBearer != null && !("Bearer " + requiredBearer).equals(auth)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        JsonNode msg = JSON.readTree(ex.getRequestBody().readAllBytes());
        String method = msg.path("method").asText();
        if (!msg.has("id")) {
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }
        if (!"initialize".equals(method) && !"s-1".equals(ex.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
            json(ex, 400, "{}");
            return;
        }
        ObjectNode result = JSON.createObjectNode();
        switch (method) {
            case "initialize" -> {
                result.put("protocolVersion", "2025-06-18");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "orders").put("version", "1");
                ex.getResponseHeaders().add("Mcp-Session-Id", "s-1");
            }
            case "tools/list" -> {
                ArrayNode tools = result.putArray("tools");
                if (!msg.path("params").has("cursor")) {
                    ObjectNode t = tools.addObject().put("name", "lookup_order").put("description", lookupDescription);
                    t.set("inputSchema", JSON.readTree("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\","
                            + "\"title\":\"Order\"}},\"required\":[\"orderId\"]}"));
                    t.putObject("annotations").put("readOnlyHint", true);
                    result.put("nextCursor", "page-2");
                } else if (offerCancel) {
                    ObjectNode t = tools.addObject().put("name", "cancel_order").put("description", "Cancel an order");
                    t.set("inputSchema", JSON.readTree("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}"));
                    t.putObject("annotations").put("destructiveHint", true);
                }
            }
            case "tools/call" -> {
                String name = msg.path("params").path("name").asText();
                calls.add(name + " " + msg.path("params").path("arguments"));
                if ("slow_order".equals(name)) {
                    sleep(3_000);
                }
                result.putArray("content").addObject().put("type", "text")
                        .put("text", "order " + msg.path("params").path("arguments").path("orderId").asText() + " is shipped;"
                                + " auth=" + auth);
                result.put("isError", "boom".equals(name));
            }
            default -> {
                json(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + msg.get("id") + ",\"error\":{\"code\":-32601,\"message\":\"no such method\"}}");
                return;
            }
        }
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", msg.get("id"));
        response.set("result", result);
        if (sse) {
            String body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                    + "event: message\ndata: " + response + "\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        } else {
            json(ex, 200, response.toString());
        }
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
