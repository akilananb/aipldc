package ai.pdlc.adapters.sandbox;

import ai.pdlc.core.port.SandboxPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against an in-test fake of the few Kubernetes API endpoints the adapter speaks. */
class KubernetesJobSandboxTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMAGE = "registry.acme/tools/lint@sha256:" + "a".repeat(64);

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, JsonNode> bodies = new ConcurrentHashMap<>();
    private volatile String jobStatus = "{\"succeeded\":1}";
    private volatile String log = "{\"ok\":true}";
    private HttpServer server;
    private KubernetesApi api;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().toString();
            requests.add(method + " " + path + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] in = exchange.getRequestBody().readAllBytes();
            if (in.length > 0) {
                bodies.put(exchange.getRequestURI().getPath(), JSON.readTree(in));
            }
            int status = 200;
            String body = "{}";
            if (method.equals("GET") && path.contains("/jobs/")) {
                body = "{\"status\":" + jobStatus + "}";
            } else if (method.equals("GET") && path.contains("/pods?")) {
                body = "{\"items\":[{\"metadata\":{\"name\":\"pod-1\"},\"status\":{\"containerStatuses\":[{\"state\":{\"terminated\":{\"exitCode\":"
                        + (jobStatus.contains("succeeded") ? 0 : 3) + "}}}]}}]}";
            } else if (method.equals("GET") && path.contains("/log?")) {
                body = log;
            } else if (method.equals("GET") && path.contains("networkpolicies/")) {
                status = 404;
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        api = new KubernetesApi(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), () -> "sa-token",
                HttpClient.newHttpClient());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private KubernetesJobSandbox sandbox(String runtimeClass) {
        return new KubernetesJobSandbox(api, new KubernetesJobSandbox.Config("pdlc-sandbox", runtimeClass, "pdlc",
                Map.of("app", "sandbox-egress-proxy"), 3128, 10));
    }

    private static SandboxPort.Request request(Duration timeout) {
        return new SandboxPort.Request("run-1", "ws-a", "run-1:2:call-x", IMAGE, "{\"file\":\"a.txt\"}", Map.of("LEVEL", "strict"),
                500, 256, timeout, "http://pdlc:tok123@egress.pdlc:3128", 1_000);
    }

    @Test
    void refusesToRunWithoutAnIsolationRuntimeClass() {
        KubernetesJobSandbox none = sandbox(null);

        assertThat(none.isolation()).isNull();
        assertThatThrownBy(() -> none.run(request(Duration.ofSeconds(5)))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused");
        assertThat(requests).isEmpty();
    }

    @Test
    void runsAJobUnderTheRuntimeClassWithEveryRestrictionAndCleansUp() {
        SandboxPort.Result result = sandbox("gvisor").run(request(Duration.ofSeconds(5)));

        assertThat(result).isEqualTo(new SandboxPort.Result(0, "{\"ok\":true}", false, false, null));
        JsonNode job = bodies.get("/apis/batch/v1/namespaces/pdlc-sandbox/jobs");
        JsonNode pod = job.path("spec").path("template").path("spec");
        JsonNode c = pod.path("containers").path(0);
        assertThat(job.path("spec").path("backoffLimit").asInt()).isZero();
        assertThat(job.path("spec").path("activeDeadlineSeconds").asInt()).isEqualTo(5);
        assertThat(pod.path("runtimeClassName").asText()).isEqualTo("gvisor");
        assertThat(pod.path("automountServiceAccountToken").asBoolean(true)).isFalse();
        assertThat(pod.path("enableServiceLinks").asBoolean(true)).isFalse();
        assertThat(pod.path("hostNetwork").asBoolean(true)).isFalse();
        assertThat(pod.path("securityContext").path("runAsNonRoot").asBoolean()).isTrue();
        assertThat(pod.path("securityContext").path("runAsUser").asInt()).isEqualTo(65534);
        assertThat(pod.path("securityContext").path("seccompProfile").path("type").asText()).isEqualTo("RuntimeDefault");
        assertThat(c.path("image").asText()).isEqualTo(IMAGE);
        assertThat(c.path("securityContext").path("readOnlyRootFilesystem").asBoolean()).isTrue();
        assertThat(c.path("securityContext").path("allowPrivilegeEscalation").asBoolean(true)).isFalse();
        assertThat(c.path("securityContext").path("capabilities").path("drop").toString()).isEqualTo("[\"ALL\"]");
        assertThat(c.path("resources").path("limits").path("cpu").asText()).isEqualTo("500m");
        assertThat(c.path("resources").path("limits").path("memory").asText()).isEqualTo("256Mi");
        assertThat(c.path("resources").path("requests")).isEqualTo(c.path("resources").path("limits"));
        assertThat(pod.path("volumes").findValues("hostPath")).isEmpty();
        assertThat(pod.path("volumes").findValuesAsText("name")).containsExactly("workspace", "tmp");
        assertThat(job.path("metadata").path("labels").path("pdlc.sandbox").asText()).isEqualTo("true");
        assertThat(job.path("metadata").path("labels").path("pdlc.run").asText()).isEqualTo("run-1");
        // Input and the proxy credential live in the per-call Secret, never in the Job spec.
        assertThat(job.toString()).doesNotContain("tok123").doesNotContain("a.txt");
        JsonNode secret = bodies.get("/api/v1/namespaces/pdlc-sandbox/secrets");
        assertThat(decode(secret, "PDLC_INPUT")).isEqualTo("{\"file\":\"a.txt\"}");
        assertThat(decode(secret, "HTTPS_PROXY")).isEqualTo("http://pdlc:tok123@egress.pdlc:3128");
        assertThat(decode(secret, "LEVEL")).isEqualTo("strict");
        String name = job.path("metadata").path("name").asText();
        assertThat(requests).anyMatch(r -> r.startsWith("DELETE /apis/batch/v1/namespaces/pdlc-sandbox/jobs/" + name
                + "?propagationPolicy=Foreground"));
        assertThat(requests).anyMatch(r -> r.startsWith("DELETE /api/v1/namespaces/pdlc-sandbox/secrets/" + name));
        assertThat(requests).allMatch(r -> r.endsWith("auth=Bearer sa-token"));
    }

    @Test
    void reportsAFailedPackageWithItsExitCodeAndCapsTheLog() {
        jobStatus = "{\"failed\":1}";
        log = "x".repeat(1_001);

        SandboxPort.Result result = sandbox("gvisor").run(request(Duration.ofSeconds(5)));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
        assertThat(result.stdout()).hasSize(1_000);
        assertThat(requests).anyMatch(r -> r.contains("/pods/pod-1/log?container=package&limitBytes=1001"));
    }

    @Test
    void aTimeoutDeletesTheJobInTheForeground() {
        jobStatus = "{\"active\":1}";

        SandboxPort.Result result = sandbox("gvisor").run(request(Duration.ofMillis(200)));

        assertThat(result.timedOut()).isTrue();
        assertThat(requests).anyMatch(r -> r.startsWith("DELETE /apis/batch/v1/namespaces/pdlc-sandbox/jobs/pdlc-sbx-")
                && r.contains("propagationPolicy=Foreground"));
    }

    @Test
    void terminateRunDeletesEveryJobAndSecretOfTheRun() {
        sandbox("gvisor").terminateRun("run-9");

        assertThat(requests).containsExactly(
                "DELETE /apis/batch/v1/namespaces/pdlc-sandbox/jobs?labelSelector=pdlc.run%3Drun-9&propagationPolicy=Foreground auth=Bearer sa-token",
                "DELETE /api/v1/namespaces/pdlc-sandbox/secrets?labelSelector=pdlc.run%3Drun-9&propagationPolicy=Foreground auth=Bearer sa-token");
    }

    @Test
    void theNetworkPolicyDeniesEverythingButTheProxyAndDns() {
        sandbox("gvisor").ensureNetworkPolicy();

        JsonNode policy = bodies.get("/apis/networking.k8s.io/v1/namespaces/pdlc-sandbox/networkpolicies");
        JsonNode spec = policy.path("spec");
        assertThat(spec.path("podSelector").path("matchLabels").path("pdlc.sandbox").asText()).isEqualTo("true");
        assertThat(spec.path("policyTypes").toString()).isEqualTo("[\"Ingress\",\"Egress\"]");
        assertThat(spec.path("ingress").size()).isZero();
        assertThat(spec.path("egress").size()).isEqualTo(2);
        JsonNode toProxy = spec.path("egress").path(0);
        assertThat(toProxy.path("to").path(0).path("podSelector").path("matchLabels").path("app").asText()).isEqualTo("sandbox-egress-proxy");
        assertThat(toProxy.path("to").path(0).path("namespaceSelector").path("matchLabels").path("kubernetes.io/metadata.name").asText())
                .isEqualTo("pdlc");
        assertThat(toProxy.path("ports").path(0).path("port").asInt()).isEqualTo(3128);
        assertThat(spec.path("egress").path(1).path("ports").findValues("port")).allMatch(p -> p.asInt() == 53);
    }

    private static String decode(JsonNode secret, String key) {
        return new String(Base64.getDecoder().decode(secret.path("data").path(key).asText()), StandardCharsets.UTF_8);
    }
}
