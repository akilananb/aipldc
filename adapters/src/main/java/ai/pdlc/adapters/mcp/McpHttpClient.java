package ai.pdlc.adapters.mcp;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A deliberately small MCP client for the Streamable HTTP transport (docs/phase-2-execution-spec.md
 * slice 2.3): {@code initialize} → {@code notifications/initialized} → paginated {@code tools/list}
 * → {@code tools/call}, over JSON or SSE responses with {@code Mcp-Session-Id}. It exists instead of
 * the MCP SDK transport so the platform's rules hold on every request: the egress guard is applied
 * to each URL, redirects are never followed, every exchange has a hard deadline (including a stalled
 * SSE body) and a byte cap, and nothing it reports contains a credential.
 */
public final class McpHttpClient {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PAGES = 50;

    /** A server tool as listed. */
    public record Tool(String name, String description, Map<String, Object> inputSchema, Map<String, Object> annotations) {
    }

    /** A {@code tools/call} result: joined text content, the server's error flag, and whether the text was cut. */
    public record CallResult(String text, boolean isError, boolean truncated) {
    }

    private final HttpClient http;
    private final Function<URI, EgressPolicy.Decision> guard;

    public McpHttpClient(Function<URI, EgressPolicy.Decision> guard) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build(),
                guard);
    }

    McpHttpClient(HttpClient http, Function<URI, EgressPolicy.Decision> guard) {
        this.http = http;
        this.guard = guard;
    }

    public Session open(URI endpoint, McpAuth auth, Duration timeout, int maxResponseBytes) {
        Session session = new Session(endpoint, auth, timeout, maxResponseBytes);
        session.initialize();
        return session;
    }

    /** One initialized MCP session; not thread-safe. */
    public final class Session {
        private final URI endpoint;
        private final McpAuth auth;
        private final Duration timeout;
        private final int maxBytes;
        private final AtomicInteger ids = new AtomicInteger();
        private String sessionId;
        private String protocolVersion = PROTOCOL_VERSION;

        private Session(URI endpoint, McpAuth auth, Duration timeout, int maxBytes) {
            this.endpoint = endpoint;
            this.auth = auth;
            this.timeout = timeout;
            this.maxBytes = maxBytes;
        }

        private void initialize() {
            ObjectNode params = JSON.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
            params.putObject("capabilities");
            params.putObject("clientInfo").put("name", "pdlc-agent-platform").put("version", "2.3");
            JsonNode result = request("initialize", params, false);
            if (result.hasNonNull("protocolVersion")) {
                protocolVersion = result.get("protocolVersion").asText();
            }
            notifyServer("notifications/initialized");
        }

        public List<Tool> listTools() {
            List<Tool> tools = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                ObjectNode params = JSON.createObjectNode();
                if (cursor != null) {
                    params.put("cursor", cursor);
                }
                JsonNode result = request("tools/list", params, false);
                for (JsonNode t : result.path("tools")) {
                    tools.add(new Tool(t.path("name").asText(), t.hasNonNull("description") ? t.get("description").asText() : null,
                            toMap(t.get("inputSchema")), toMap(t.get("annotations"))));
                }
                cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
                if (cursor == null) {
                    return tools;
                }
            }
            throw new McpException("tools/list returned more than " + MAX_PAGES + " pages", false);
        }

        /** Calls a tool. {@link McpException#maybeSent()} tells a caller whether a write may have happened. */
        public CallResult callTool(String name, JsonNode arguments) {
            ObjectNode params = JSON.createObjectNode().put("name", name);
            params.set("arguments", arguments);
            JsonNode result = request("tools/call", params, true);
            StringBuilder text = new StringBuilder();
            for (JsonNode part : result.path("content")) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append("text".equals(part.path("type").asText()) ? part.path("text").asText()
                        : "[" + part.path("type").asText("content") + " omitted]");
            }
            if (text.length() == 0 && result.has("structuredContent")) {
                text.append(result.get("structuredContent").toString());
            }
            boolean truncated = text.length() > maxBytes;
            return new CallResult(truncated ? text.substring(0, maxBytes) : text.toString(),
                    result.path("isError").asBoolean(false), truncated);
        }

        private void notifyServer(String method) {
            ObjectNode body = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
            exchange(body, null, false);
        }

        private JsonNode request(String method, JsonNode params, boolean effectful) {
            int id = ids.incrementAndGet();
            ObjectNode body = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
            body.set("params", params);
            JsonNode message = exchange(body, id, effectful);
            if (message.has("error")) {
                JsonNode error = message.get("error");
                throw new McpException(method + " failed: " + error.path("message").asText("JSON-RPC error " + error.path("code").asInt()),
                        false);
            }
            return message.path("result");
        }

        /** One HTTP POST; returns the JSON-RPC response with {@code id}, or null for a notification. */
        private JsonNode exchange(ObjectNode body, Integer id, boolean effectful) {
            for (int attempt = 1; ; attempt++) {
                EgressPolicy.Decision destination = guard.apply(endpoint);
                if (!destination.allowed()) {
                    throw new McpException("destination not allowed: " + destination.reason(), false);
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("MCP-Protocol-Version", protocolVersion)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
                if (sessionId != null) {
                    request.header("Mcp-Session-Id", sessionId);
                }
                String authorization = auth.header();
                if (authorization != null) {
                    request.header("Authorization", authorization);
                }
                HttpResponse<InputStream> response;
                try {
                    response = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                            .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException | ExecutionException e) {
                    Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                    String what = cause instanceof HttpTimeoutException || cause instanceof TimeoutException
                            ? "timed out after " + timeout.toSeconds() + "s" : "request failed (" + cause.getClass().getSimpleName() + ")";
                    throw new McpException(body.path("method").asText() + " " + what, effectful);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new McpException(body.path("method").asText() + " interrupted", effectful);
                }
                int status = response.statusCode();
                if (status == 401 && attempt == 1) {
                    close(response);
                    if (auth.onUnauthorized(response.headers().firstValue("WWW-Authenticate").orElse(""))) {
                        continue;
                    }
                    throw new McpException("the MCP server rejected the connection's credentials (401)", false);
                }
                if (status >= 300 && status < 400) {
                    close(response);
                    throw new McpException("the MCP server answered " + status + "; redirects are not followed", false);
                }
                if (status >= 400) {
                    close(response);
                    throw new McpException(body.path("method").asText() + " failed: HTTP " + status, status >= 500 && effectful);
                }
                response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
                if (id == null) {
                    close(response);
                    return null;
                }
                boolean sse = response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream");
                return readResponse(response, id, sse, effectful);
            }
        }

        private JsonNode readResponse(HttpResponse<InputStream> response, int id, boolean sse, boolean effectful) {
            InputStream in = response.body();
            CompletableFuture<JsonNode> read = CompletableFuture.supplyAsync(() -> sse ? readSse(in, id) : readJson(in));
            try {
                return read.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                close(response);
                throw new McpException("the MCP server's response did not complete within " + timeout.toSeconds() + "s", effectful);
            } catch (ExecutionException e) {
                close(response);
                if (e.getCause() instanceof McpException m) {
                    throw m;
                }
                throw new McpException("the MCP server's response could not be read", effectful);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close(response);
                throw new McpException("interrupted", effectful);
            }
        }

        private JsonNode readJson(InputStream in) {
            try (in) {
                byte[] bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new McpException("the MCP server's response exceeded " + maxBytes + " bytes", false);
                }
                return JSON.readTree(bytes);
            } catch (IOException e) {
                throw new McpException("the MCP server's response is not valid JSON", false);
            }
        }

        /** Reads SSE events until the JSON-RPC response with {@code id}; server requests and notifications are skipped. */
        private JsonNode readSse(InputStream in, int id) {
            long read = 0;
            try (in; BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = lines.readLine()) != null) {
                    read += line.length() + 1;
                    if (read > maxBytes) {
                        throw new McpException("the MCP server's event stream exceeded " + maxBytes + " bytes", false);
                    }
                    if (line.startsWith("data:")) {
                        data.append(line.substring(5).stripLeading());
                    } else if (line.isEmpty() && data.length() > 0) {
                        JsonNode message = JSON.readTree(data.toString());
                        data.setLength(0);
                        if (message.path("id").asInt(-1) == id && (message.has("result") || message.has("error"))) {
                            return message;
                        }
                    }
                }
                throw new McpException("the MCP server closed the event stream without a response", false);
            } catch (IOException e) {
                throw new McpException("the MCP server's event stream could not be read", false);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return node == null || !node.isObject() ? null : JSON.convertValue(node, Map.class);
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
