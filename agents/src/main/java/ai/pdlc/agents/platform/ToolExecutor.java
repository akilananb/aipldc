package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.ToolStore.CallRecord;
import ai.pdlc.agents.platform.ToolStore.PinnedTool;
import ai.pdlc.adapters.mcp.McpException;
import ai.pdlc.adapters.mcp.McpHttpClient;
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
 *   <li>a WRITE runs only under an APPROVED approval for this exact call - same tool version and
 *       canonical args hash - and behind a recorded effect intent (slice 2.2); until a decision,
 *       the run pauses;</li>
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
    static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String NEEDS_OPERATOR = "NEEDS_OPERATOR";

    /** Where a call happens: the run, its retry attempt, the model turn and the run's deadline. */
    public record Context(UUID runId, String workspaceId, int attempt, int turn, Instant deadline) {
    }

    /**
     * {@code content} is what the model sees for this call (JSON text). A non-null {@code pause}
     * means the call could not complete yet (slice 2.2): the run stops here and resumes this same
     * call after the approval is decided or the operator resolves the effect.
     */
    public record Outcome(boolean allowed, String content, Pause pause) {
        public Outcome(boolean allowed, String content) {
            this(allowed, content, null);
        }
    }

    /** {@code kind} is AWAITING_APPROVAL (id = approval) or NEEDS_OPERATOR (id = effect). */
    public record Pause(String kind, UUID id, int escalateAfterMinutes, int expireAfterMinutes) {
    }

    private final ToolStore tools;
    private final SecretsPort secrets;
    private final EgressPolicy egress;
    private final McpToolCaller mcp;
    private final HttpClient http;
    private final Clock clock;

    @Autowired
    public ToolExecutor(ToolStore tools, SecretsPort secrets, EgressPolicy egress, McpToolCaller mcp) {
        this(tools, secrets, egress, mcp, Clock.systemUTC());
    }

    ToolExecutor(ToolStore tools, SecretsPort secrets, EgressPolicy egress, McpToolCaller mcp, Clock clock) {
        this.tools = tools;
        this.secrets = secrets;
        this.egress = egress;
        this.mcp = mcp;
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
        String connectionProblem = connectionProblem(tool, spec);
        if (connectionProblem != null) {
            return deny(ctx, call, version, args, connectionProblem);
        }
        URI uri;
        try {
            uri = spec.isMcp() ? URI.create(tool.baseUrl()) : buildUri(tool.baseUrl(), spec, args.args());
        } catch (IllegalArgumentException e) {
            return deny(ctx, call, version, args, e.getMessage());
        }
        EgressPolicy.Decision destination = egress.check(uri);
        if (!destination.allowed()) {
            return deny(ctx, call, version, args, "destination not allowed: " + destination.reason());
        }
        ToolStore.Approval approval = null;
        if (ToolSpec.WRITE.equals(spec.effect())) {
            approval = tools.approval(ctx.runId(), ctx.turn(), call.id()).orElse(null);
            if (approval == null || "PENDING".equals(approval.status())) {
                return awaitApproval(ctx, call, version, args, spec);
            }
            String problem = approvalProblem(approval, version, args);
            if (problem != null) {
                return deny(ctx, call, version, args, problem);
            }
        }
        Duration remaining = Duration.between(clock.instant(), ctx.deadline());
        Duration timeout = Duration.ofSeconds(spec.timeoutSeconds());
        if (remaining.compareTo(timeout) < 0) {
            timeout = remaining;
        }
        if (timeout.isNegative() || timeout.isZero()) {
            return deny(ctx, call, version, args, "the run deadline has passed");
        }
        if (spec.isMcp()) {
            return mcp(ctx, call, version, args, spec, tool, timeout, approval);
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
        return approval == null
                ? send(ctx, call, version, args, spec, uri, timeout, secret)
                : write(ctx, call, version, args, spec, uri, timeout, secret, approval);
    }

    /**
     * An MCP tool call (slice 2.3), after every policy check above has passed. The server's current
     * definition is compared with the reviewed one first - a changed or removed tool is refused
     * without being called. A WRITE records its effect intent before {@code tools/call}; MCP has no
     * idempotency key, so an outcome that may have happened always waits for an operator.
     */
    private Outcome mcp(Context ctx, ModelInvoker.ToolCall call, int version, ToolArgs.Parsed args, ToolSpec spec,
                        PinnedTool tool, Duration timeout, ToolStore.Approval approval) {
        long started = System.nanoTime();
        String key = ctx.runId() + ":" + ctx.turn() + ":" + call.id();
        if (approval != null) {
            ToolStore.Effect known = tools.effect(key).orElse(null);
            if (known != null && ("SUCCEEDED".equals(known.state()) || "FAILED".equals(known.state()))) {
                return replayed(ctx, call, version, args, known);
            }
            if (known != null && ("SENT".equals(known.state()) || "UNKNOWN".equals(known.state()))) {
                return needsOperator(ctx, call, version, args, known);
            }
        }
        McpToolCaller.Session session;
        try {
            session = mcp.open(tool.connection(), timeout, spec.maxResponseBytes());
            String drift = mcp.driftProblem(session, ctx.runId() + "|" + ctx.attempt() + "|" + tool.connectionId(), spec);
            if (drift != null) {
                return deny(ctx, call, version, args, drift);
            }
        } catch (IllegalStateException | McpException e) {
            return failed(ctx, call, version, args, started, e.getMessage());
        }
        if (approval == null) {
            try {
                McpHttpClient.CallResult result = session.session().callTool(spec.mcpTool(), args.args());
                return mcpResult(ctx, call, version, args, spec, session, result, started, null, null);
            } catch (McpException e) {
                return failed(ctx, call, version, args, started, e.getMessage());
            }
        }
        ToolStore.Effect effect = tools.intend(ctx.runId(), approval.id(), call.name(), version, args.hash(), key);
        if (!"INTENDED".equals(effect.state())) {
            // Another attempt got here between our read and the insert: never send twice.
            return needsOperator(ctx, call, version, args, effect);
        }
        tools.markSent(effect.id());
        try {
            McpHttpClient.CallResult result = session.session().callTool(spec.mcpTool(), args.args());
            return mcpResult(ctx, call, version, args, spec, session, result, started, effect, approval.id());
        } catch (McpException e) {
            if (e.maybeSent()) {
                tools.markUnknown(effect.id());
                tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                        args.args().toString(), args.hash(), ALLOWED, null, null, elapsed(started), null, false,
                        e.getMessage() + "; outcome unknown"));
                return new Outcome(false, null, new Pause(NEEDS_OPERATOR, effect.id(), 0, 0));
            }
            String content = JSON.createObjectNode().put("error", e.getMessage()).toString();
            tools.finish(effect.id(), "FAILED", null, content);
            return failed(ctx, call, version, args, started, e.getMessage());
        }
    }

    private Outcome mcpResult(Context ctx, ModelInvoker.ToolCall call, int version, ToolArgs.Parsed args, ToolSpec spec,
                              McpToolCaller.Session session, McpHttpClient.CallResult result, long started, ToolStore.Effect effect,
                              UUID approvalId) {
        String text = redact(result.text(), session.secretToRedact());
        boolean truncated = result.truncated() || text.length() > spec.maxResponseBytes();
        if (text.length() > spec.maxResponseBytes()) {
            text = text.substring(0, spec.maxResponseBytes());
        }
        String content = JSON.createObjectNode().put("isError", result.isError()).put("truncated", truncated)
                .put("content", text).toString();
        if (effect != null) {
            tools.finish(effect.id(), result.isError() ? "FAILED" : "SUCCEEDED", null, content);
        }
        long millis = elapsed(started);
        tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                args.args().toString(), args.hash(), ALLOWED, approvalId == null ? null : "approved (" + approvalId + ")", null, millis,
                (long) text.length(), truncated, result.isError() ? "the MCP tool reported an error" : null));
        log.info("Run {} turn {}: mcp tool {} v{} ({}) -> {} in {} ms", ctx.runId(), ctx.turn(), call.name(), version,
                spec.mcpTool(), result.isError() ? "error" : "ok", millis);
        return new Outcome(true, content);
    }

    /** Creates (or re-reads) the call's approval and pauses the run on it; nothing is sent. */
    private Outcome awaitApproval(Context ctx, ModelInvoker.ToolCall call, int version, ToolArgs.Parsed args, ToolSpec spec) {
        ToolStore.Approval approval = tools.requestApproval(ctx.runId(), ctx.workspaceId(), ctx.turn(), call.id(), call.name(),
                version, args.args().toString(), args.hash());
        if (!"PENDING".equals(approval.status())) {
            // Decided between our read and insert: take the normal path on resume.
            return awaitApprovalAgain(approval, spec);
        }
        tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                args.args().toString(), args.hash(), PENDING_APPROVAL, "waiting for approval " + approval.id(),
                null, null, null, false, null));
        log.info("Run {} turn {}: tool {} v{} waits for approval {}", ctx.runId(), ctx.turn(), call.name(), version,
                approval.id());
        return awaitApprovalAgain(approval, spec);
    }

    private static Outcome awaitApprovalAgain(ToolStore.Approval approval, ToolSpec spec) {
        ToolSpec.Approval timing = spec.approvalOrDefault();
        return new Outcome(false, null, new Pause(AWAITING_APPROVAL, approval.id(), timing.escalateAfter(), timing.expireAfter()));
    }

    /** Why a decided approval does not authorize this exact call (null = it does). */
    private static String approvalProblem(ToolStore.Approval approval, int version, ToolArgs.Parsed args) {
        return switch (approval.status()) {
            case "APPROVED" -> approval.toolVersion() != version || !approval.argsHash().equals(args.hash())
                    ? "approval " + approval.id() + " was given for different arguments or tool version"
                    : null;
            case "REJECTED" -> "the write was rejected by " + approval.decidedBy()
                    + (approval.reason() == null || approval.reason().isBlank() ? "" : ": " + approval.reason());
            case "EXPIRED" -> "the write's approval expired without a decision";
            default -> "the write's approval is " + approval.status();
        };
    }

    /**
     * An approved write: the effect intent is recorded (keyed by run:turn:callId) before anything
     * is sent, so every retry finds it. A write whose outcome is already known is never resent. One
     * whose request may have been sent is resent only when the target honors {@code Idempotency-Key};
     * otherwise the run waits for an operator.
     */
    private Outcome write(Context ctx, ModelInvoker.ToolCall call, int version, ToolArgs.Parsed args, ToolSpec spec,
                          URI uri, Duration timeout, String secret, ToolStore.Approval approval) {
        String key = ctx.runId() + ":" + ctx.turn() + ":" + call.id();
        ToolStore.Effect effect = tools.intend(ctx.runId(), approval.id(), call.name(), version, args.hash(), key);
        switch (effect.state()) {
            case "SUCCEEDED", "FAILED" -> {
                return replayed(ctx, call, version, args, effect);
            }
            case "SENT", "UNKNOWN" -> {
                if (!spec.idempotentByHeader()) {
                    return needsOperator(ctx, call, version, args, effect);
                }
            }
            default -> { }
        }
        for (int send = 1; ; send++) {
            tools.markSent(effect.id());
            long started = System.nanoTime();
            try {
                Exchange exchange = exchange(spec, uri, timeout, secret, args.args(),
                        spec.idempotentByHeader() ? effect.idempotencyKey() : null);
                ObjectNode content = exchange.content();
                String state = exchange.status() >= 200 && exchange.status() < 300 ? "SUCCEEDED" : "FAILED";
                tools.finish(effect.id(), state, exchange.status(), content.toString());
                tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                        args.args().toString(), args.hash(), ALLOWED, "approved (" + approval.id() + ")", exchange.status(),
                        exchange.millis(), (long) exchange.kept(), exchange.truncated(), exchange.error()));
                log.info("Run {} turn {}: write {} v{} -> HTTP {} (effect {}, send {})", ctx.runId(), ctx.turn(),
                        call.name(), version, exchange.status(), effect.id(), send);
                return new Outcome(true, content.toString());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                tools.markUnknown(effect.id());
                String error = e instanceof HttpTimeoutException ? "timed out after " + timeout.toSeconds() + "s"
                        : "request failed (" + e.getClass().getSimpleName() + ")";
                tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                        args.args().toString(), args.hash(), ALLOWED, null, null, elapsed(started), null, false,
                        error + "; outcome unknown"));
                if (!spec.idempotentByHeader() || send >= 2 || Thread.currentThread().isInterrupted()) {
                    return new Outcome(false, null, new Pause(NEEDS_OPERATOR, effect.id(), 0, 0));
                }
                // The target dedups on the key, so resending the same request once is safe.
            }
        }
    }

    /** A write whose outcome is already known is reported again, never resent. */
    private Outcome replayed(Context ctx, ModelInvoker.ToolCall call, int version, ToolArgs.Parsed args, ToolStore.Effect effect) {
        String content = effect.resultContent() != null ? effect.resultContent()
                : JSON.createObjectNode().put("outcome", effect.state())
                        .put("note", "recorded by operator " + effect.resolvedBy()
                                + (effect.note() == null ? "" : ": " + effect.note())).toString();
        tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                args.args().toString(), args.hash(), ALLOWED, null, effect.httpStatus(), null, null, false,
                "effect already " + effect.state().toLowerCase() + "; not resent"));
        return new Outcome(true, content);
    }

    private Outcome needsOperator(Context ctx, ModelInvoker.ToolCall call, int version, ToolArgs.Parsed args,
                                  ToolStore.Effect effect) {
        tools.markUnknown(effect.id());
        tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                args.args().toString(), args.hash(), ALLOWED, null, null, null, null, false,
                "outcome unknown and the target has no idempotency support; waiting for an operator"));
        log.info("Run {} turn {}: write {} effect {} needs an operator", ctx.runId(), ctx.turn(), call.name(), effect.id());
        return new Outcome(false, null, new Pause(NEEDS_OPERATOR, effect.id(), 0, 0));
    }

    /** One HTTP exchange's result as the model will see it (redacted, capped). */
    private record Exchange(int status, ObjectNode content, int kept, boolean truncated, long millis, String error) {
    }

    private Exchange exchange(ToolSpec spec, URI uri, Duration timeout, String secret, JsonNode args, String idempotencyKey)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
        if (secret != null) {
            request.header("Authorization", "Bearer " + secret);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if ("GET".equals(spec.method()) || "DELETE".equals(spec.method())) {
            request.method(spec.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(spec.method(), HttpRequest.BodyPublishers.ofString(body(spec, args).toString()));
        }
        long started = System.nanoTime();
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = in.readNBytes(spec.maxResponseBytes() + 1);
        }
        boolean truncated = bytes.length > spec.maxResponseBytes();
        int kept = Math.min(bytes.length, spec.maxResponseBytes());
        String body = redact(new String(bytes, 0, kept, StandardCharsets.UTF_8), secret);
        int status = response.statusCode();
        ObjectNode content = JSON.createObjectNode().put("status", status);
        String error = null;
        if (status >= 300 && status < 400) {
            error = "redirect not followed";
            content.put("error", error);
        } else {
            content.put("truncated", truncated).put("body", body);
        }
        return new Exchange(status, content, kept, truncated, elapsed(started), error);
    }

    private Outcome send(Context ctx, ModelInvoker.ToolCall call, int version, ToolArgs.Parsed args, ToolSpec spec,
                         URI uri, Duration timeout, String secret) {
        long started = System.nanoTime();
        try {
            Exchange exchange = exchange(spec, uri, timeout, secret, args.args(), null);
            tools.record(new CallRecord(ctx.runId(), ctx.attempt(), ctx.turn(), call.id(), call.name(), version,
                    args.args().toString(), args.hash(), ALLOWED, null, exchange.status(), exchange.millis(),
                    (long) exchange.kept(), exchange.truncated(), exchange.error()));
            log.info("Run {} turn {}: tool {} v{} -> HTTP {} in {} ms{}", ctx.runId(), ctx.turn(), call.name(), version,
                    exchange.status(), exchange.millis(), exchange.truncated() ? " (truncated)" : "");
            return new Outcome(true, exchange.content().toString());
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

    private String connectionProblem(PinnedTool tool, ToolSpec spec) {
        if (tool.connectionId() == null) {
            return "the tool's connection no longer exists";
        }
        String kind = spec.isMcp() ? "MCP_SERVER" : "HTTP_API";
        if (!kind.equals(tool.connectionKind())) {
            return "connection " + tool.connectionId() + " is not an " + kind + " connection";
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
