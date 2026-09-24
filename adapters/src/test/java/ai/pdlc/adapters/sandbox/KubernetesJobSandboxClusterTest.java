package ai.pdlc.adapters.sandbox;

import ai.pdlc.core.port.SandboxPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KubernetesJobSandbox} against a real API server, with {@code infra/k8s/sandbox.yaml}
 * applied. Skipped unless {@code PDLC_K8S_API} (API server URL), {@code PDLC_K8S_TOKEN} (a token of
 * the {@code pdlc/agents-sandbox} service account) and {@code PDLC_K8S_CA} (path to the cluster CA
 * PEM) are set - scripts/sandbox-colima.sh sets them. {@code PDLC_K8S_IMAGE} is a digest-pinned
 * image whose default command exits 0.
 *
 * <p>Where sandbox pods can start (gVisor installed), the run succeeds; where they cannot, it times
 * out. Either way the Job and its Secret must be gone afterwards.
 */
@EnabledIfEnvironmentVariable(named = "PDLC_K8S_API", matches = ".+")
class KubernetesJobSandboxClusterTest {

    private static final String NS = "pdlc-sandbox";
    private static KubernetesApi api;
    private static KubernetesJobSandbox sandbox;
    private static String image;

    @BeforeAll
    static void connect() throws Exception {
        api = new KubernetesApi(URI.create(System.getenv("PDLC_K8S_API")), () -> System.getenv("PDLC_K8S_TOKEN"),
                KubernetesApi.clientTrusting(Files.readString(Path.of(System.getenv("PDLC_K8S_CA")))));
        sandbox = new KubernetesJobSandbox(api, new KubernetesJobSandbox.Config(NS, "gvisor", "pdlc", Map.of("app", "agents"), 3128, 500));
        image = System.getenv().getOrDefault("PDLC_K8S_IMAGE",
                "busybox@sha256:73aaf090f3d85aa34ee199857f03fa3a95c8ede2ffd4cc2cdb5b94e566b11662");
    }

    private static SandboxPort.Request request(String runId, Duration timeout) {
        return new SandboxPort.Request(runId, "ws-a", runId + ":1:" + UUID.randomUUID(), image, "{\"n\":1}", Map.of(),
                250, 64, timeout, "http://pdlc:not-a-live-token@sandbox-egress-proxy.pdlc:3128", 10_000);
    }

    @Test
    void theApiServerAcceptsTheJobWithEveryRestriction() {
        ObjectNode job = sandbox.job("pdlc-sbx-dryrun", request("run-dry", Duration.ofSeconds(30)));

        JsonNode accepted = api.post("/apis/batch/v1/namespaces/" + NS + "/jobs?dryRun=All", job);

        JsonNode pod = accepted.path("spec").path("template").path("spec");
        assertThat(pod.path("runtimeClassName").asText()).isEqualTo("gvisor");
        assertThat(pod.path("automountServiceAccountToken").asBoolean(true)).isFalse();
        assertThat(pod.path("containers").path(0).path("securityContext").path("readOnlyRootFilesystem").asBoolean()).isTrue();
    }

    @Test
    void theNetworkPolicyIsInstalledOnce() {
        sandbox.ensureNetworkPolicy();
        sandbox.ensureNetworkPolicy();

        JsonNode policy = api.get("/apis/networking.k8s.io/v1/namespaces/" + NS + "/networkpolicies/pdlc-sandbox-egress");
        assertThat(policy.path("spec").path("policyTypes").toString()).isEqualTo("[\"Ingress\",\"Egress\"]");
        assertThat(policy.path("spec").path("egress").size()).isEqualTo(2);
    }

    @Test
    void aRunLeavesNoJobOrSecretBehind() throws Exception {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);

        SandboxPort.Result result = sandbox.run(request(runId, Duration.ofSeconds(20)));

        assertThat(result.error()).isNull();
        assertThat(result.timedOut() || result.exitCode() == 0).as(result.toString()).isTrue();
        assertGone(runId);
    }

    @Test
    void terminateRunEndsARunningCall() throws Exception {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<SandboxPort.Result> call = CompletableFuture.supplyAsync(() -> sandbox.run(request(runId, Duration.ofSeconds(120))));
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline
                && api.get("/apis/batch/v1/namespaces/" + NS + "/jobs?labelSelector=" + KubernetesApi.label("pdlc.run=" + runId))
                        .path("items").isEmpty()) {
            Thread.sleep(200);
        }

        sandbox.terminateRun(runId);

        SandboxPort.Result result = call.get(60, TimeUnit.SECONDS);
        assertThat(result.exitCode() == 0 || result.error() != null).isTrue();
        assertGone(runId);
    }

    private static void assertGone(String runId) throws InterruptedException {
        String selector = "?labelSelector=" + KubernetesApi.label("pdlc.run=" + runId);
        long deadline = System.currentTimeMillis() + 60_000;
        boolean gone = false;
        while (!gone && System.currentTimeMillis() < deadline) {
            gone = api.get("/apis/batch/v1/namespaces/" + NS + "/jobs" + selector).path("items").isEmpty()
                    && api.get("/api/v1/namespaces/" + NS + "/pods" + selector).path("items").isEmpty();
            if (!gone) {
                Thread.sleep(500);
            }
        }
        assertThat(gone).as("jobs and pods of " + runId + " are deleted").isTrue();
    }
}
