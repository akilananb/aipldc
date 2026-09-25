package ai.pdlc.agents.platform;

import ai.pdlc.agents.platform.A2aRunnerTest.FakeRuns;
import ai.pdlc.agents.platform.RunStore.Invocation;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.platform.GrpcDescriptors;
import ai.pdlc.core.workflow.AgentRunWorkflow.AgentRunOutcome;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.DynamicMessage;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GrpcAgentRunner} against a real Netty gRPC server over TLS (optionally requiring a client
 * certificate) whose handlers are built from the same registered descriptors the agent carries.
 */
class GrpcAgentRunnerTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-00000000067c");
    private static final String SECRET = "grpc-LIVE-secret-77";
    static final String SET = descriptorSet();

    /** The service. Summarise input: anything (ok), fail, slow (never answers), drop (processes, then UNAVAILABLE). */
    static final class Service {
        final Server server;
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicInteger processed = new AtomicInteger();
        final Map<String, DynamicMessage> byKey = new ConcurrentHashMap<>();
        final CountDownLatch slowStarted = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicReference<Metadata> headers = new AtomicReference<>();
        private final GrpcDescriptors.Resolved summarise = GrpcDescriptors.resolve(SET, "demo.ReportAgent", "Summarise");
        private final GrpcDescriptors.Resolved report = GrpcDescriptors.resolve(SET, "demo.ReportAgent", "Report");

        Service(ClientAuth clientAuth) throws IOException {
            ServerServiceDefinition definition = ServerServiceDefinition.builder("demo.ReportAgent")
                    .addMethod(GrpcAgentRunner.descriptor(summarise), ServerCalls.asyncUnaryCall(this::summarise))
                    .addMethod(GrpcAgentRunner.descriptor(report), ServerCalls.asyncServerStreamingCall(this::report))
                    .build();
            ServerInterceptor capture = new ServerInterceptor() {
                @Override
                public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata h, ServerCallHandler<Q, R> next) {
                    headers.set(h);
                    return next.startCall(call, h);
                }
            };
            server = NettyServerBuilder.forPort(0)
                    .sslContext(GrpcSslContexts.forServer(resource("server.pem"), resource("server-key.pem"))
                            .trustManager(resource("ca.pem")).clientAuth(clientAuth).build())
                    .addService(ServerInterceptors.intercept(definition, capture))
                    .build().start();
        }

        String target() {
            return "grpcs://localhost:" + server.getPort();
        }

        private String field(DynamicMessage m, String name) {
            return String.valueOf(m.getField(m.getDescriptorForType().findFieldByName(name)));
        }

        private DynamicMessage reply(String summary) {
            return DynamicMessage.newBuilder(summarise.output())
                    .setField(summarise.output().findFieldByName("summary"), summary).build();
        }

        private void summarise(DynamicMessage request, StreamObserver<DynamicMessage> out) {
            Metadata h = headers.get();
            String key = h.get(GrpcAgentRunner.IDEMPOTENCY_KEY);
            String input = field(request, "input");
            calls.add("Summarise " + input + " prompt=" + field(request, "prompt") + " auth=" + h.get(GrpcAgentRunner.AUTHORIZATION)
                    + " key=" + key);
            DynamicMessage stored = key == null ? null : byKey.get(key);
            if (stored != null) {
                out.onNext(stored);
                out.onCompleted();
                return;
            }
            switch (input) {
                case "fail" -> out.onError(Status.INVALID_ARGUMENT.withDescription("region unknown (token " + SECRET + ")").asRuntimeException());
                case "slow" -> {
                    ((ServerCallStreamObserver<DynamicMessage>) out).setOnCancelHandler(() -> {
                        events.add("cancelled by the client");
                        cancelled.countDown();
                    });
                    slowStarted.countDown();
                }
                case "drop" -> {
                    processed.incrementAndGet();
                    byKey.put(key, reply("Summary of drop"));
                    out.onError(Status.UNAVAILABLE.withDescription("backend restarted").asRuntimeException());
                }
                default -> {
                    processed.incrementAndGet();
                    out.onNext(reply("Summary of " + input));
                    out.onCompleted();
                }
            }
        }

        private void report(DynamicMessage request, StreamObserver<DynamicMessage> out) {
            int n = Integer.parseInt(field(request, "input"));
            calls.add("Report " + n);
            for (int i = 1; i <= n; i++) {
                out.onNext(reply("line " + i));
            }
            out.onCompleted();
        }
    }

    private Service service;
    private final FakeRuns runs = new FakeRuns();
    private final EgressPolicy egress = new EgressPolicy(Set.of("localhost"));
    private final Map<String, String> vault = Map.of(
            "kv://grpc-key", SECRET,
            "kv://grpc-ca", text("ca.pem"),
            "kv://wrong-ca", text("wrong-ca.pem"),
            "kv://grpc-client-cert", text("client.pem"),
            "kv://grpc-client-key", text("client-key.pem"));
    private final GrpcAgentRunner runner = new GrpcAgentRunner(runs, ref -> {
        String v = vault.get(ref);
        if (v == null) {
            throw new IllegalStateException("no secret " + ref);
        }
        return v;
    }, egress, Clock.systemUTC(), Duration.ofMillis(20));

    @AfterEach
    void stop() {
        if (service != null) {
            service.server.shutdownNow();
        }
    }

    private AgentSpec spec(String method, String prompt, String promptField, boolean idempotent, Integer maxMessages, int timeout) {
        return new AgentSpec("Calls the report agent", "grpc", prompt, List.of(new AgentSpec.Variable("input", null, true)), null,
                new AgentSpec.Limits(null, timeout), null, null, null, null,
                new AgentSpec.GrpcBinding("report-grpc", SET, "demo.ReportAgent", method, promptField, idempotent, maxMessages));
    }

    private AgentRunOutcome invoke(AgentSpec spec, String input, String target, RunStore.TlsRefs tls, String prompt) {
        runs.invocation = new Invocation(RUN, "engineering", "RUNNING", "grpc", 1, ContentHash.ofAgent("G", spec), "G",
                ContentHash.canonicalJson(spec), Map.of("input", input), null, null, null, "report-grpc", "ACTIVE", null,
                "API_KEY", "kv://grpc-key", target, 0, 0, "GRPC_AGENT", null, true, tls);
        return runner.invoke(runs.invocation, spec, prompt);
    }

    private AgentRunOutcome invoke(AgentSpec spec, String input) {
        return invoke(spec, input, service.target(), new RunStore.TlsRefs("kv://grpc-ca", "kv://grpc-client-cert", "kv://grpc-client-key"),
                null);
    }

    @Test
    void aUnaryCallOverMutualTlsReturnsTheReplyAsJson() throws IOException {
        service = new Service(ClientAuth.REQUIRE);
        AgentSpec spec = spec("Summarise", "Summarise {{input}}", "prompt", false, null, 30);

        AgentRunOutcome outcome = invoke(spec, "Q3", service.target(),
                new RunStore.TlsRefs("kv://grpc-ca", "kv://grpc-client-cert", "kv://grpc-client-key"), "Summarise Q3");

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(runs.output).isEqualTo("{\"summary\":\"Summary of Q3\"}");
        assertThat(service.calls).containsExactly("Summarise Q3 prompt=Summarise Q3 auth=Bearer " + SECRET + " key=" + RUN + ":0");
        assertThat(runs.sends).containsEntry(RUN + ":0", "ACKED");
        assertThat(runs.remote.dialect()).isEqualTo("grpc-unary");
        assertThat(runs.remote.state()).isEqualTo("COMPLETED");
        assertThat(runs.remote.statusText()).isEqualTo("OK");
    }

    @Test
    void aServerStreamIsCollectedUpToMaxMessages() throws IOException {
        service = new Service(ClientAuth.REQUIRE);

        assertThat(invoke(spec("Report", null, null, false, 5, 30), "3").status()).isEqualTo("SUCCEEDED");
        assertThat(runs.output).isEqualTo("[{\"summary\":\"line 1\"},{\"summary\":\"line 2\"},{\"summary\":\"line 3\"}]");
        assertThat(runs.remote.dialect()).isEqualTo("grpc-server-stream");

        runs.sends.clear();
        runs.status = "RUNNING";
        assertThat(invoke(spec("Report", null, null, false, 2, 30), "3").status()).isEqualTo("FAILED");
        assertThat(runs.error).isEqualTo("the service sent more than 2 messages (maxMessages)");
    }

    @Test
    void aStatusCodeFailsTheRunHonestlyAndTheCredentialIsRedacted() throws IOException {
        service = new Service(ClientAuth.REQUIRE);

        AgentRunOutcome outcome = invoke(spec("Summarise", null, null, false, null, 30), "fail");

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(runs.error).isEqualTo("the service answered INVALID_ARGUMENT: region unknown (token [REDACTED])");
        assertThat(runs.remote.state()).isEqualTo("FAILED");
        assertThat(runs.remote.statusText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void theDeadlineIsTheAgentsRemainingTime() throws Exception {
        service = new Service(ClientAuth.REQUIRE);

        AgentRunOutcome outcome = invoke(spec("Summarise", null, null, false, null, 1), "slow");

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(runs.error).isEqualTo("the call did not finish within the agent's time (DEADLINE_EXCEEDED)");
        assertThat(service.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cancellingTheRunCancelsTheCallOnTheWire() throws Exception {
        service = new Service(ClientAuth.REQUIRE);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread call = new Thread(() -> {
            try {
                invoke(spec("Summarise", null, null, false, null, 30), "slow");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        call.start();
        assertThat(service.slowStarted.await(10, TimeUnit.SECONDS)).isTrue();

        runs.status = "CANCELLED";
        runner.cancelRemote(RUN.toString());
        call.join(10_000);

        assertThat(service.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(service.events).containsExactly("cancelled by the client");
        assertThat(runs.remote.cancel()).isEqualTo("SIGNALLED");
        assertThat(thrown.get()).isInstanceOfSatisfying(ApplicationFailure.class, f -> assertThat(f.isNonRetryable()).isTrue());
    }

    @Test
    void anUnknownOutcomeIsNotResentUnlessTheMethodIsIdempotent() throws IOException {
        service = new Service(ClientAuth.REQUIRE);

        assertThatThrownBy(() -> invoke(spec("Summarise", null, null, false, null, 30), "drop"))
                .isInstanceOfSatisfying(ApplicationFailure.class, f -> assertThat(f.isNonRetryable()).isTrue());
        assertThat(runs.error).startsWith("outcome unknown: the call ended UNAVAILABLE: backend restarted").contains("was not resent");
        assertThatThrownBy(() -> invoke(spec("Summarise", null, null, false, null, 30), "drop")).isInstanceOf(ApplicationFailure.class);
        assertThat(service.calls).hasSize(1);

        runs.sends.clear();
        runs.status = "RUNNING";
        runs.remote = null;
        service.byKey.clear(); // a fresh call identity for the second agent
        AgentSpec idempotent = spec("Summarise", null, null, true, null, 30);
        assertThatThrownBy(() -> invoke(idempotent, "drop"))
                .isInstanceOfSatisfying(ApplicationFailure.class, f -> assertThat(f.isNonRetryable()).isFalse());
        assertThat(invoke(idempotent, "drop").status()).isEqualTo("SUCCEEDED");
        assertThat(runs.output).isEqualTo("{\"summary\":\"Summary of drop\"}");
        assertThat(service.processed).hasValue(2); // one per agent: the idempotent resend was replayed, not processed
        assertThat(service.calls).hasSize(3);
    }

    @Test
    void tlsIsVerifiedAgainstThePinnedCaAndMutualTlsNeedsTheClientCertificate() throws IOException {
        service = new Service(ClientAuth.REQUIRE);
        AgentSpec spec = spec("Summarise", null, null, false, null, 30);

        assertThatThrownBy(() -> invoke(spec, "Q3", service.target(), new RunStore.TlsRefs("kv://wrong-ca", "kv://grpc-client-cert",
                "kv://grpc-client-key"), null)).isInstanceOfSatisfying(ApplicationFailure.class, f -> assertThat(f.isNonRetryable()).isTrue());
        assertThat(runs.error).startsWith("TLS with localhost:" + service.server.getPort() + " failed");
        assertThat(service.calls).isEmpty();

        runs.sends.clear();
        runs.status = "RUNNING";
        assertThatThrownBy(() -> invoke(spec, "Q3", service.target(), new RunStore.TlsRefs("kv://grpc-ca", null, null), null))
                .isInstanceOf(ApplicationFailure.class);
        assertThat(runs.error).startsWith("TLS with localhost:").containsIgnoringCase("certificate");
        assertThat(runs.sends).containsEntry(RUN + ":0", "INTENDED");
        assertThat(service.calls).isEmpty();
    }

    @Test
    void theTargetIsCheckedBeforeAnythingIsSent() {
        AgentSpec spec = spec("Summarise", null, null, false, null, 30);

        assertThatThrownBy(() -> invoke(spec, "Q3", "grpcs://10.1.2.3:443", null, null)).hasMessageContaining("not an allowed destination");
        assertThatThrownBy(() -> invoke(spec, "Q3", "grpc://localhost:9", null, null))
                .hasMessageContaining("would send its credential in plaintext");
        assertThat(runs.sends).isEmpty();
    }

    static String descriptorSet() {
        FieldDescriptorProto.Builder input = FieldDescriptorProto.newBuilder().setName("input").setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING).setJsonName("input");
        FieldDescriptorProto.Builder prompt = FieldDescriptorProto.newBuilder().setName("prompt").setNumber(2)
                .setType(FieldDescriptorProto.Type.TYPE_STRING).setJsonName("prompt");
        FieldDescriptorProto.Builder summary = FieldDescriptorProto.newBuilder().setName("summary").setNumber(1)
                .setType(FieldDescriptorProto.Type.TYPE_STRING).setJsonName("summary");
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("demo/report.proto").setPackage("demo").setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder().setName("SummariseRequest").addField(input).addField(prompt))
                .addMessageType(DescriptorProto.newBuilder().setName("SummariseReply").addField(summary))
                .addService(ServiceDescriptorProto.newBuilder().setName("ReportAgent")
                        .addMethod(MethodDescriptorProto.newBuilder().setName("Summarise")
                                .setInputType(".demo.SummariseRequest").setOutputType(".demo.SummariseReply"))
                        .addMethod(MethodDescriptorProto.newBuilder().setName("Report").setServerStreaming(true)
                                .setInputType(".demo.SummariseRequest").setOutputType(".demo.SummariseReply")))
                .build();
        return Base64.getEncoder().encodeToString(FileDescriptorSet.newBuilder().addFile(file).build().toByteArray());
    }

    private static InputStream resource(String name) {
        return GrpcAgentRunnerTest.class.getResourceAsStream("/grpc-tls/" + name);
    }

    private static String text(String name) {
        try (InputStream in = resource(name)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
