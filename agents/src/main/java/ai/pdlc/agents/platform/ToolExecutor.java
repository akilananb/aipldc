package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.ToolStore.CallRecord;
import ai.pdlc.agents.platform.ToolStore.PinnedTool;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.platform.ToolArgs;
import ai.pdlc.core.platform.ToolSpec;
import ai.pdlc.core.platform.ToolSpecValidator;
import ai.pdlc.core.port.SecretsPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * The only path by which a tool runs (docs/phase-2-execution-spec.md slice 2.1). For each call the
 * model requested it decides, in order, and records the decision and outcome in
 * {@code platform_tool_calls} - allowed or not:
 * <ol>
 *   <li>the tool is pinned by this agent version (by id) - descriptions and names from the model
 *       are never trusted for authorization;</li>
 *   <li>the pinned version still exists, matches its content hash and the tool is not retired;</li>
 *   <li>the arguments parse and satisfy the pinned input schema (no undeclared arguments);</li>
 *   <li>the effect is READ - WRITE tools need an approval, which arrives in slice 2.2;</li>
 *   <li><em>now</em>: the connection is an active, unexpired {@code HTTP_API} connection still
 *       granted to the run's workspace;</li>
 *   <li>the URL (connection base + path template, path values percent-encoded) passes the
 *       {@link EgressPolicy};</li>
 *   <li>the {@code kv://} credential is resolved last, sent only as a Bearer header and redacted
 *       from anything returned to the model;</li>
 *   <li>the call runs with the tool's timeout (capped by the run deadline), redirects are never
 *       followed, and the body is cut at {@code maxResponseBytes} (flagged as truncated).</li>
 * </ol>
 * A denial is returned to the model as an error result; it does not fail the run.
 *
 * <p>Known gap, closed by slice 2.4's egress proxy: the HTTP client resolves the host again when
 * it connects, so a name whose DNS answer changes between the check and the connect (rebinding)
 * is not pinned to the checked addresses.
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String ALLOWED = "ALLOWED";
    static final String DENIED = "DENIED";

    /** Where a call happens: the run, its retry attempt, the model turn and the run's deadline. */
    public record Context(UUID runId, String workspaceId, int attempt, int turn, Instant deadline) {
    }

    /** {@code content} is what the model sees for this call (JSON text). */
    public record Outcome(boolean allowed, String content) {
    }

    private final ToolStore tools;
    private final SecretsPort secrets;
    private final EgressPolicy egress;
    private final HttpClient http;
    private final Clock clock;

    @Autowired
    public ToolExecutor(ToolStore tools, SecretsPort secrets, EgressPolicy egress) {
        this(tools, secrets, egress, Clock.systemUTC());
    }

    ToolExecutor(ToolStore tools, SecretsPort secrets, EgressPolicy egress, Clock clock) {
        this.tools = tools;
        this.secrets = secrets;
        this.egress = egress;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** @param pinned the agent version's tool pins: tool id → version */
    public Outcome execute(Context ctx, Map<String, Integer> pinned, ModelInvoker.ToolCall call) {
        String toolId = call.name();
        Integer version = pinned.get(toolId);
        if (version == null) {
            return deny(ctx, call, null, null, "tool " + toolId + " is not available to this agent");
        }
        PinnedTool tool = tools.load(ctx.workspaceId(), toolId, version).orElse(null);
        if (tool == null) {
            return deny(ctx, call, version, null, "tool " + toolId + " v" + version + " does not exist");
        }
        ToolSpec spec = ContentHash.read(tool.specJson(), ToolSpec.class);
        if (!ContentHash.ofTool(tool.name(), spec).equals(tool.contentHash())) {
            return deny(ctx, call, version, null, "tool " + toolId + " v" + version + " content does not match its hash");
        }
        if ("RETIRED".equals(tool.toolStatus())) {
            return deny(ctx, call, version, null, "tool " + toolId + " is retired");
        }
        ToolArgs.Parsed args = ToolArgs.parse(call.argumentsJson(), spec.inputSchema());
        if (!args.valid()) {
            return deny(ctx, call, version, args, "invalid arguments: " + String.join("; ", args.errors()));
        }
        if (!ToolSpec.READ.equals(spec.effect())) {
            return deny(ctx, call, version, args, "tool " + toolId + " has effect " + spec.effect()
                    + " and requires an approval (slice 2.2); it was not executed");
        }
        String connectionProblem = connectionProblem(tool);
        if (connectionProblem != null) {
            return deny(ctx, call, version, args, connectionProblem);
        }
        URI uri;
        try {
            uri = buildUri(tool.baseUrl(), spec, args.args());
        } catch (IllegalArgumentException e) {
            return deny(ctx, call, version, args, e.getMessage());
        }
        EgressPolicy.Decision destination = egress.check(uri);
        if (!destination.allowed()) {
            return deny(ctx, call, version, args, "destination not allowed: " + destination.reason());
        }
        Duration remaining = Duration.between(clock.instant(), ctx.deadline());
        Duration timeout = Duration.ofSeconds(spec.timeoutSeconds());
        if (remaining.compareTo(timeout) < 0) {
            timeout = remaining;
        }
        if (timeout.isNegative() || timeout.isZero()) {
            return deny(ctx, call, version, args, "the run deadline has passed");
        }
        String secret = null;
        if ("API_KEY".equals(tool.authType())) {
            try {
                secret = secrets.resolve(tool.secretRef());
            } catch (RuntimeException e) {
                secret = null;
            }
            if (secret == null || secret.isBlank()) {
                return deny(ctx, call, version, args, "the credential for connection " + tool.connectionId()
                        + " could not be resolved");
            }
        }
        return send(ctx, call, version, args, spec, uri, timeout, secret);
    }

    private Outcome send(Context ctx, ModelInvoker.ToolCall call, int version, ToolArgs.Parsed args, ToolSpec spec,
                         URI uri, Duration timeout, String secret) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
        if (secret != null) {
            request.header("Authorization", "Bearer " + secret);
        }
        if ("GET".equals(spec.method()) || "DELETE".equals(spec.method())) {
            request.method(spec.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(spec.method(), HttpRequest.BodyPublishers.ofString(body(spec, args.args()).toString()));
        }
        long started = System.nanoTime();
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readNBytes(spec.maxResponseBytes() + 1);
            }
            boolean truncated = bytes.length > spec.maxResponseBytes();
            int kept = Math.min(bytes.length, spec.maxResponseBytes());
            String body = redact(new String(bytes, 0, kept, StandardCharsets.UTF_8), secret);
            long millis = elapsed(started);
            int status = response.statusCode();
            ObjectNode content = JSON.createObjectNode().put("status", status);
            String error = null;
            if (status >= 300 && status < 400) {
                error = "redirect not followed";
                content.put("error", error);
            } else {
                content.put("truncated", truncated).put("body", body);
            }
            tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                    args.args().toString(), args.hash(), ALLOWED, null, status, millis, (long) kept, truncated, error));
            log.info("Run {} turn {}: tool {} v{} -> HTTP {} in {} ms{}", ctx.runId(), ctx.turn(), call.name(), version,
                    status, millis, truncated ? " (truncated)" : "");
            return new Outcome(true, content.toString());
        } catch (HttpTimeoutException e) {
            return failed(ctx, call, version, args, started, "timed out after " + timeout.toSeconds() + "s");
        } catch (IOException e) {
            return failed(ctx, call, version, args, started, "request failed (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(ctx, call, version, args, started, "interrupted");
        }
    }

    private Outcome failed(Context ctx, ModelInvoker.ToolCall call, int version, ToolArgs.Parsed args, long started,
                           String error) {
        tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                args.args().toString(), args.hash(), ALLOWED, null, null, elapsed(started), null, false, error));
        log.info("Run {} turn {}: tool {} v{} failed: {}", ctx.runId(), ctx.turn(), call.name(), version, error);
        return new Outcome(true, JSON.createObjectNode().put("error", error).toString());
    }

    private Outcome deny(Context ctx, ModelInvoker.ToolCall call, Integer version, ToolArgs.Parsed args, String reason) {
        boolean parsed = args != null && args.args() != null;
        tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                parsed ? args.args().toString() : null, parsed ? args.hash() : null, DENIED, reason,
                null, null, null, false, null));
        log.info("Run {} turn {}: tool {} denied: {}", ctx.runId(), ctx.turn(), call.name(), reason);
        return new Outcome(false, JSON.createObjectNode().put("error", "denied by policy: " + reason).toString());
    }

    private String connectionProblem(PinnedTool tool) {
        if (tool.connectionId() == null) {
            return "the tool's connection no longer exists";
        }
        if (!"HTTP_API".equals(tool.connectionKind())) {
            return "connection " + tool.connectionId() + " is not an HTTP_API connection";
        }
        if (!"ACTIVE".equals(tool.connectionStatus())) {
            return "connection " + tool.connectionId() + " is revoked";
        }
        if (tool.connectionExpiresAt() != null && !tool.connectionExpiresAt().toInstant().isAfter(clock.instant())) {
            return "connection " + tool.connectionId() + " expired";
        }
        if (!tool.granted()) {
            return "connection " + tool.connectionId() + " is not granted to workspace " + tool.workspaceId();
        }
        return null;
    }

    /** Base URL + path template with percent-encoded values; GET/DELETE put the other arguments in the query. */
    static URI buildUri(String baseUrl, ToolSpec spec, JsonNode args) {
        Set<String> pathParams = ToolSpecValidator.pathParams(spec.path());
        String path = spec.path();
        for (String param : pathParams) {
            JsonNode value = args.get(param);
            if (value == null || value.isNull() || value.isContainerNode()) {
                throw new IllegalArgumentException("path argument " + param + " must be a string or number");
            }
            String text = value.asText();
            if (text.isEmpty() || text.equals(".") || text.equals("..")) {
                throw new IllegalArgumentException("path argument " + param + " is not a valid path segment");
            }
            path = path.replace("{" + param + "}", encode(text));
        }
        StringBuilder url = new StringBuilder(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .append(path);
        if ("GET".equals(spec.method()) || "DELETE".equals(spec.method())) {
            StringJoiner query = new StringJoiner("&");
            for (Iterator<Map.Entry<String, JsonNode>> it = args.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!pathParams.contains(e.getKey()) && !e.getValue().isNull()) {
                    JsonNode v = e.getValue();
                    query.add(encode(e.getKey()) + "=" + encode(v.isValueNode() ? v.asText() : v.toString()));
                }
            }
            if (query.length() > 0) {
                url.append('?').append(query);
            }
        }
        return URI.create(url.toString());
    }

    private static ObjectNode body(ToolSpec spec, JsonNode args) {
        ObjectNode body = JSON.createObjectNode();
        Set<String> pathParams = ToolSpecValidator.pathParams(spec.path());
        args.fields().forEachRemaining(e -> {
            if (!pathParams.contains(e.getKey())) {
                body.set(e.getKey(), e.getValue());
            }
        });
        return body;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String redact(String text, String secret) {
        return secret == null || secret.isEmpty() ? text : text.replace(secret, "[REDACTED]");
    }

    private static long elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
