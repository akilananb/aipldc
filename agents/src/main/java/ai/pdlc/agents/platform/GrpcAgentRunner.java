package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.agents.platform.RunStore.RemoteTask;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.platform.GrpcDescriptors;
import ai.pdlc.core.platform.OutputSchema;
import ai.pdlc.core.port.SecretsPort;
import ai.pdlc.core.workflow.AgentRunActivities;
import ai.pdlc.core.workflow.AgentRunWorkflow.AgentRunOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a {@code grpc} agent (docs/phase-2-execution-spec.md slice 2.6b) against an existing gRPC
 * agent service - calling exactly the one unary or server-streaming method its published version
 * registered, with the message types from the version's own descriptors. Nothing is discovered by
 * reflection.
 *
 * <ul>
 *   <li><b>At call time</b> the connection must still be an active, unexpired {@code GRPC_AGENT}
 *       granted to the workspace. The target host passes the {@link EgressPolicy} and the channel
 *       connects to exactly the address that was checked (the authority keeps SNI and hostname
 *       verification), with no proxy, no retries and a 1 MB inbound cap. TLS uses the connection's
 *       pinned CA and, for mTLS, its client certificate - PEM resolved from {@code kv://} references
 *       only here. Plaintext exists only for {@code grpc://} targets, which carry no credential.</li>
 *   <li><b>Call identity.</b> The call {@code run:0} is recorded INTENDED → SENT → ACKED and carries
 *       {@code idempotency-key: run:0}. A call whose outcome is unknown (the connection broke or the
 *       service failed after the call started) is resent only for {@code idempotent} bindings;
 *       otherwise the run fails as "outcome unknown" and nothing is resent.</li>
 *   <li><b>Deadline</b>: the agent's remaining time. <b>Streams</b> are collected up to
 *       {@code maxMessages} and 1 MB; more cancels the call and fails the run.</li>
 *   <li><b>Cancellation</b>: while the call is in flight the invoking activity checks the run every
 *       second; once it is no longer RUNNING the call is cancelled on the wire and the remote cancel
 *       recorded as {@code SIGNALLED} - gRPC has no acknowledgement, so none is claimed.</li>
 * </ul>
 */
@Component
public class GrpcAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(GrpcAgentRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_BYTES = 1_000_000;
    static final String UNARY_DIALECT = "grpc-unary";
    static final String STREAM_DIALECT = "grpc-server-stream";
    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> IDEMPOTENCY_KEY = Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> RUN_ID = Metadata.Key.of("x-pdlc-run", Metadata.ASCII_STRING_MARSHALLER);
    private static final Pattern TARGET = Pattern.compile("^(grpcs?)://(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+):([0-9]{1,5})$");
    /** Failure codes after which the service may or may not have acted on the call. */
    private static final Set<Status.Code> UNKNOWN_OUTCOME = Set.of(Status.Code.UNAVAILABLE, Status.Code.UNKNOWN,
            Status.Code.INTERNAL, Status.Code.CANCELLED);

    private final RunStore runs;
    private final SecretsPort secrets;
    private final EgressPolicy egress;
    private final Clock clock;
    private final Duration checkEvery;
    /** Calls this process has in flight, so a cancel handled here reaches the wire at once. */
    private final Map<UUID, ClientCall<?, ?>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public GrpcAgentRunner(RunStore runs, SecretsPort secrets, EgressPolicy egress) {
        this(runs, secrets, egress, Clock.systemUTC(), Duration.ofSeconds(1));
    }

    /** {@code checkEvery}: how often an in-flight call re-reads its run's status (shortened in tests). */
    GrpcAgentRunner(RunStore runs, SecretsPort secrets, EgressPolicy egress, Clock clock, Duration checkEvery) {
        this.runs = runs;
        this.secrets = secrets;
        this.egress = egress;
        this.clock = clock;
        this.checkEvery = checkEvery;
    }

    /** A checked gRPC target: the host and port to present, the one address to connect to, and credentials. */
    private record Target(String host, int port, InetAddress address, boolean tls, String bearer, SslContext ssl) {
    }

    AgentRunOutcome invoke(Invocation run, AgentSpec spec, String prompt) {
        UUID id = run.runId();
        Instant started = clock.instant();
        Instant deadline = started.plusMillis(spec.limits().timeoutSeconds() * 1000L - run.activeMs());
        try {
            AgentSpec.GrpcBinding grpc = spec.grpc();
            GrpcDescriptors.Resolved method;
            try {
                method = GrpcDescriptors.resolve(grpc.descriptorSet(), grpc.service(), grpc.method());
            } catch (GrpcDescriptors.InvalidDescriptorsException e) {
                return failRunAndReject(id, "the agent's registered descriptors cannot be used: " + e.getMessage());
            }
            if (!method.callable()) {
                return failRunAndReject(id, method.fullMethodName() + " is not a unary or server-streaming method");
            }
            String dialect = method.serverStreaming() ? STREAM_DIALECT : UNARY_DIALECT;
            DynamicMessage request = request(id, method, run.inputs(), grpc.promptField(), prompt);
            Target target = connect(run);

            String messageId = id + ":0";
            String sendState = runs.intendSend(id, messageId);
            if (("SENT".equals(sendState) || "ACKED".equals(sendState)) && !grpc.resendable()) {
                return failRunAndReject(id, "outcome unknown: call " + messageId + " may have reached the service; "
                        + "the agent does not declare the method idempotent, so it was not resent");
            }
            long remainingMs = Duration.between(clock.instant(), deadline).toMillis();
            if (remainingMs <= 0) {
                return failRun(id, "the agent's time ran out before the call was made");
            }
            runs.saveRemoteTask(id, dialect, null, null, "WORKING", null);
            return call(run, spec, method, dialect, target, request, messageId, remainingMs);
        } finally {
            runs.addActiveMs(id, Duration.between(started, clock.instant()).toMillis());
        }
    }

    private AgentRunOutcome call(Invocation run, AgentSpec spec, GrpcDescriptors.Resolved method, String dialect, Target target,
                                 DynamicMessage request, String messageId, long remainingMs) {
        UUID id = run.runId();
        int maxMessages = method.serverStreaming() ? spec.grpc().maxMessagesOrDefault() : 1;
        ManagedChannel channel = channel(target);
        try {
            ClientCall<DynamicMessage, DynamicMessage> call = channel.newCall(descriptor(method),
                    CallOptions.DEFAULT.withDeadlineAfter(remainingMs, TimeUnit.MILLISECONDS));
            Collector collector = new Collector(maxMessages);
            Metadata headers = new Metadata();
            if (target.bearer() != null) {
                headers.put(AUTHORIZATION, "Bearer " + target.bearer());
            }
            headers.put(IDEMPOTENCY_KEY, messageId);
            headers.put(RUN_ID, id.toString());
            runs.setSendState(id, messageId, "SENT");
            inFlight.put(id, call);
            try {
                call.start(collector, headers);
                call.request(maxMessages + 1);
                call.sendMessage(request);
                call.halfClose();
                while (!collector.await(checkEvery)) {
                    if (collector.overflow != null) {
                        call.cancel(collector.overflow, null);
                        continue;
                    }
                    String status = runs.load(id).map(Invocation::status).orElse("UNKNOWN");
                    if (!"RUNNING".equals(status)) {
                        call.cancel("the platform run is " + status, null);
                        runs.setRemoteCancel(id, "SIGNALLED");
                        collector.await(Duration.ofSeconds(5));
                        throw reject("the run is " + status + "; the gRPC call was cancelled");
                    }
                }
            } finally {
                inFlight.remove(id);
            }
            if (collector.overflow == null && collector.status.getCode() == Status.Code.CANCELLED) {
                // Cancelled here by cancelRemote: the run stopped, so this is its cancellation, not an unknown outcome.
                String status = runs.load(id).map(Invocation::status).orElse("UNKNOWN");
                if (!"RUNNING".equals(status)) {
                    runs.setRemoteCancel(id, "SIGNALLED");
                    throw reject("the run is " + status + "; the gRPC call was cancelled");
                }
            }
            return finish(run, spec, dialect, collector, messageId, target);
        } finally {
            channel.shutdownNow();
        }
    }

    private AgentRunOutcome finish(Invocation run, AgentSpec spec, String dialect, Collector collector, String messageId,
                                   Target target) {
        UUID id = run.runId();
        Status status = collector.status;
        if (collector.overflow != null) {
            runs.setSendState(id, messageId, "ACKED");
            runs.saveRemoteTask(id, dialect, null, null, "FAILED", "CANCELLED");
            return failRun(id, collector.overflow);
        }
        if (status.isOk()) {
            runs.setSendState(id, messageId, "ACKED");
            runs.saveRemoteTask(id, dialect, null, null, "COMPLETED", "OK");
            return complete(id, spec, dialect, collector.messages, target);
        }
        String code = status.getCode().name();
        String description = redact(status.getDescription(), target.bearer());
        Throwable cause = status.getCause();
        if (cause instanceof ConnectException || causedBy(cause, ConnectException.class) != null) {
            runs.setSendState(id, messageId, "INTENDED");
            throw retryable("could not connect to " + target.host() + ":" + target.port());
        }
        SSLException tls = causedBy(cause, SSLException.class);
        if (tls != null) {
            runs.setSendState(id, messageId, "INTENDED");
            runs.saveRemoteTask(id, dialect, null, null, "FAILED", code);
            return failRunAndReject(id, "TLS with " + target.host() + ":" + target.port() + " failed: " + innermostMessage(tls));
        }
        if (status.getCode() == Status.Code.DEADLINE_EXCEEDED) {
            runs.saveRemoteTask(id, dialect, null, null, "FAILED", code);
            return failRun(id, "the call did not finish within the agent's time (DEADLINE_EXCEEDED)");
        }
        if (UNKNOWN_OUTCOME.contains(status.getCode())) {
            runs.saveRemoteTask(id, dialect, null, null, "WORKING", code);
            String why = "the call ended " + code + (description == null ? "" : ": " + description);
            if (spec.grpc().resendable()) {
                throw retryable(why + "; resending " + messageId + " with the same idempotency-key");
            }
            return failRunAndReject(id, "outcome unknown: " + why + "; call " + messageId
                    + " was not resent (the agent does not declare the method idempotent)");
        }
        runs.setSendState(id, messageId, "ACKED");
        runs.saveRemoteTask(id, dialect, null, null, "FAILED", code);
        return failRun(id, "the service answered " + code + (description == null ? "" : ": " + description));
    }

    private AgentRunOutcome complete(UUID id, AgentSpec spec, String dialect, List<DynamicMessage> messages, Target target) {
        JsonNode result;
        try {
            if (STREAM_DIALECT.equals(dialect)) {
                ArrayNode array = JSON.createArrayNode();
                for (DynamicMessage m : messages) {
                    array.add(JSON.readTree(print(m)));
                }
                result = array;
            } else if (messages.size() == 1) {
                result = JSON.readTree(print(messages.get(0)));
            } else {
                return failRun(id, "the service answered OK with " + messages.size() + " messages to a unary call");
            }
        } catch (IOException e) {
            return failRun(id, "the service's reply could not be converted to JSON");
        }
        String text = redact(result.toString(), target.bearer());
        String outputJson = null;
        if (spec.outputSchema() != null) {
            List<String> findings = OutputSchema.validate(spec.outputSchema(), result);
            if (!findings.isEmpty()) {
                String error = "Service result does not match outputSchema: " + String.join("; ", findings);
                runs.fail(id, error, text);
                throw reject(error);
            }
            outputJson = text;
        }
        if (!runs.complete(id, text, outputJson, null, null)) {
            return new AgentRunOutcome(id.toString(), runs.load(id).map(Invocation::status).orElse("UNKNOWN"), null);
        }
        return new AgentRunOutcome(id.toString(), "SUCCEEDED", null);
    }

    /**
     * Best effort, and only for gRPC runs: cancels this process's in-flight call for the run on the wire.
     * When the call runs elsewhere, the invoking activity sees the run stop within a second and cancels it
     * there; until then the request is recorded as REQUESTED.
     */
    public void cancelRemote(String runId) {
        UUID id = UUID.fromString(runId);
        RemoteTask known = runs.remoteTask(id).orElse(null);
        if (known == null || known.dialect() == null || !known.dialect().startsWith("grpc")) {
            return;
        }
        if (Set.of("COMPLETED", "FAILED", "CANCELED").contains(known.state())) {
            return;
        }
        ClientCall<?, ?> call = inFlight.get(id);
        if (call != null) {
            call.cancel("the platform run was cancelled", null);
            runs.setRemoteCancel(id, "SIGNALLED");
        } else if (known.cancel() == null) {
            runs.setRemoteCancel(id, "REQUESTED");
        }
    }

    /** The request message: each input into its field, the rendered prompt into {@code promptField}. */
    private DynamicMessage request(UUID id, GrpcDescriptors.Resolved method, Map<String, String> inputs, String promptField,
                                   String prompt) {
        ObjectNode json = JSON.createObjectNode();
        for (Map.Entry<String, String> input : inputs.entrySet()) {
            Descriptors.FieldDescriptor field = method.input().findFieldByName(input.getKey());
            if (field == null) {
                return failRunAndReject(id, "input " + input.getKey() + " is not a field of " + method.input().getFullName());
            }
            json.set(input.getKey(), fieldValue(field, input.getValue()));
        }
        if (promptField != null && prompt != null) {
            json.put(promptField, prompt);
        }
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(method.input());
        try {
            JsonFormat.parser().merge(json.toString(), builder);
        } catch (InvalidProtocolBufferException e) {
            return failRunAndReject(id, "the inputs do not fit " + method.input().getFullName() + ": " + e.getMessage());
        }
        return builder.build();
    }

    /** Scalars go in as text (protobuf JSON parses numbers, enums and timestamps from strings); lists and messages as JSON. */
    private static JsonNode fieldValue(Descriptors.FieldDescriptor field, String value) {
        boolean structured = field.isRepeated() || field.isMapField()
                || (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE
                && !field.getMessageType().getFile().getPackage().equals("google.protobuf"));
        if (structured && value != null) {
            try {
                return JSON.readTree(value);
            } catch (IOException e) {
                return JSON.getNodeFactory().textNode(value);
            }
        }
        return JSON.getNodeFactory().textNode(value);
    }

    private static String print(DynamicMessage message) throws InvalidProtocolBufferException {
        return JsonFormat.printer().preservingProtoFieldNames().omittingInsignificantWhitespace().print(message);
    }

    static MethodDescriptor<DynamicMessage, DynamicMessage> descriptor(GrpcDescriptors.Resolved method) {
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(method.serverStreaming() ? MethodDescriptor.MethodType.SERVER_STREAMING : MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(method.service(), method.method()))
                .setRequestMarshaller(new DynamicMarshaller(method.input()))
                .setResponseMarshaller(new DynamicMarshaller(method.output()))
                .build();
    }

    /** Protobuf wire format for the registered message types; nothing else is parsed. */
    static final class DynamicMarshaller implements MethodDescriptor.Marshaller<DynamicMessage> {
        private final Descriptors.Descriptor type;

        DynamicMarshaller(Descriptors.Descriptor type) {
            this.type = type;
        }

        @Override
        public InputStream stream(DynamicMessage value) {
            return new ByteArrayInputStream(value.toByteArray());
        }

        @Override
        public DynamicMessage parse(InputStream stream) {
            try {
                return DynamicMessage.parseFrom(type, stream);
            } catch (IOException e) {
                throw Status.INTERNAL.withDescription("the reply is not a valid " + type.getFullName()).withCause(e)
                        .asRuntimeException();
            }
        }
    }

    /** Collects replies up to the cap and waits for the call to close. */
    private static final class Collector extends ClientCall.Listener<DynamicMessage> {
        private final int maxMessages;
        private final CountDownLatch closed = new CountDownLatch(1);
        final List<DynamicMessage> messages = new ArrayList<>();
        private long bytes;
        volatile String overflow;
        volatile Status status;

        Collector(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        @Override
        public void onMessage(DynamicMessage message) {
            bytes += message.getSerializedSize();
            if (messages.size() >= maxMessages) {
                overflow = "the service sent more than " + maxMessages + (maxMessages == 1 ? " message" : " messages (maxMessages)");
            } else if (bytes > MAX_BYTES) {
                overflow = "the service's replies exceeded " + MAX_BYTES + " bytes";
            } else {
                messages.add(message);
            }
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
            this.status = status;
            closed.countDown();
        }

        boolean await(Duration d) {
            try {
                return closed.await(d.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw retryable("interrupted");
            }
        }
    }

    private ManagedChannel channel(Target target) {
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(new InetSocketAddress(target.address(), target.port()))
                .overrideAuthority(authority(target.host(), target.port()))
                .proxyDetector(address -> null)
                .disableRetry()
                .maxInboundMessageSize(MAX_BYTES)
                .userAgent("pdlc-agent-platform");
        if (target.tls()) {
            builder.sslContext(target.ssl());
        } else {
            builder.usePlaintext();
        }
        return builder.build();
    }

    private static String authority(String host, int port) {
        return host + ":" + port;
    }

    private Target connect(Invocation run) {
        if (!"GRPC_AGENT".equals(run.connectionKind())) {
            throw reject("connection " + run.connectionId() + " is not a GRPC_AGENT connection");
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
        Matcher m = run.baseUrl() == null ? null : TARGET.matcher(run.baseUrl());
        if (m == null || !m.matches()) {
            throw reject("connection " + run.connectionId() + " has no grpcs://host:port target");
        }
        boolean tls = "grpcs".equals(m.group(1));
        String host = m.group(2);
        int port = Integer.parseInt(m.group(3));
        EgressPolicy.Decision decision = egress.check(URI.create("https://" + host + ":" + port));
        if (!decision.allowed() || decision.addresses().isEmpty()) {
            throw reject("connection " + run.connectionId() + " is not an allowed destination: " + decision.reason());
        }
        String bearer = null;
        if ("API_KEY".equals(run.authType())) {
            if (!tls) {
                throw reject("connection " + run.connectionId() + " would send its credential in plaintext; use grpcs://");
            }
            bearer = resolve(run, run.secretRef(), "credential");
        }
        SslContext ssl = tls ? sslContext(run) : null;
        return new Target(host.startsWith("[") ? host.substring(1, host.length() - 1) : host, port,
                decision.addresses().get(0), tls, bearer, ssl);
    }

    private SslContext sslContext(Invocation run) {
        RunStore.TlsRefs refs = run.tls();
        try {
            SslContextBuilder builder = GrpcSslContexts.forClient();
            if (refs != null && refs.caRef() != null) {
                builder.trustManager(pem(resolve(run, refs.caRef(), "pinned CA")));
            }
            if (refs != null && refs.clientCertRef() != null && refs.clientKeyRef() != null) {
                builder.keyManager(pem(resolve(run, refs.clientCertRef(), "client certificate")),
                        pem(resolve(run, refs.clientKeyRef(), "client key")));
            }
            return builder.build();
        } catch (SSLException | IllegalArgumentException e) {
            throw reject("connection " + run.connectionId() + " has TLS material that cannot be used (PEM certificates and a "
                    + "PKCS#8 PEM key are required)");
        }
    }

    private String resolve(Invocation run, String ref, String what) {
        try {
            return secrets.resolve(ref);
        } catch (RuntimeException e) {
            throw reject("the " + what + " for connection " + run.connectionId() + " could not be resolved");
        }
    }

    private static InputStream pem(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static <T extends Throwable> T causedBy(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }

    /** The most specific reason in a TLS failure (the outer exception is often a generic engine error). */
    private static String innermostMessage(Throwable t) {
        String message = t.getMessage();
        for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
            if (c.getMessage() != null && !c.getMessage().isBlank()) {
                message = c.getMessage();
            }
        }
        return message;
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
