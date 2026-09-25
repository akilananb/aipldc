package ai.pdlc.adapters.sandbox;

import ai.pdlc.core.port.SandboxPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real containers under gVisor. Skipped unless a Docker engine with the {@code runsc} runtime and
 * the {@code busybox:1.36} image is available (see docs/phase-2-execution-spec.md slice 2.4 for
 * the one-time setup).
 */
class DockerSandboxTest {

    private static String image;

    @BeforeAll
    static void requireDockerWithGvisor() throws Exception {
        String runtimes = run("docker", "info", "--format", "{{range $k,$v := .Runtimes}}{{$k}} {{end}}");
        assumeTrue(runtimes != null && runtimes.contains("runsc"), "docker with the runsc runtime is not available");
        assumeTrue(run("docker", "image", "inspect", "busybox:1.36") != null, "busybox:1.36 is not pulled");
        // The package's command comes from its image; this test image runs whatever $SCRIPT says.
        // Built with commit (no registry round trip) from the local busybox.
        String base = run("docker", "create", "busybox:1.36");
        assumeTrue(base != null, "busybox container could not be created");
        String committed = run("docker", "commit", "--change", "ENTRYPOINT [\"sh\", \"-c\", \"eval \\\"$SCRIPT\\\"\"]",
                base.trim(), "pdlc-sandbox-test:1");
        run("docker", "rm", base.trim());
        assumeTrue(committed != null, "test image could not be committed");
        image = run("docker", "image", "inspect", "--format", "{{.Id}}", "pdlc-sandbox-test:1").trim();
    }

    private final DockerSandbox sandbox = new DockerSandbox(new DockerSandbox.Config("docker", "runsc", "pdlc-sandbox-test-net",
            "pdlc-sandbox-test-relay", "relay", 3128));

    private static SandboxPort.Request request(String runId, String script, Duration timeout, int maxOutput) {
        return new SandboxPort.Request(runId, "ws-a", runId + ":1:" + UUID.randomUUID(), image, "{\"n\":1}", Map.of("SCRIPT", script),
                500, 128, timeout, null, maxOutput);
    }

    private SandboxPort.Result exec(String script) {
        return sandbox.run(request("run-" + UUID.randomUUID(), script, Duration.ofSeconds(60), 10_000));
    }

    @Test
    void refusesToRunWithoutAnIsolationRuntime() {
        DockerSandbox none = new DockerSandbox(new DockerSandbox.Config("docker", null, "n", "r", "relay", 3128));

        assertThat(none.isolation()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> none.run(request("r", "true", Duration.ofSeconds(5), 100)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runsUnderGvisorAsNobodyWithAReadOnlyRootAndOnlyTheGivenInput() {
        SandboxPort.Result result = exec("dmesg | head -1; id -u; touch /escape 2>/dev/null || echo ro-root; echo \"$PDLC_INPUT\"");

        assertThat(result.error()).isNull();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Starting gVisor").contains("65534").contains("ro-root").contains("{\"n\":1}");
    }

    @Test
    void cannotReadAHostFile() throws IOException {
        Path sentinel = Files.createTempFile("pdlc-host-sentinel", ".txt");
        Files.writeString(sentinel, "host-secret-" + UUID.randomUUID());
        try {
            SandboxPort.Result result = exec("cat " + sentinel + " 2>/dev/null || echo no-such-file; ls /var/run/docker.sock 2>/dev/null || echo no-socket");

            assertThat(result.stdout()).contains("no-such-file").contains("no-socket").doesNotContain("host-secret");
        } finally {
            Files.deleteIfExists(sentinel);
        }
    }

    @Test
    void eachCallGetsAFreshWorkspace() {
        exec("echo other-run-artifact > /workspace/artifact.txt; echo other > /tmp/t");

        SandboxPort.Result next = exec("ls -A /workspace /tmp; cat /workspace/artifact.txt 2>/dev/null || echo clean");

        assertThat(next.stdout()).contains("clean").doesNotContain("other-run-artifact");
    }

    @Test
    void hasNoNetworkWithoutAProxy() {
        SandboxPort.Result result = exec("wget -q -T 3 -O- http://1.1.1.1/ 2>&1 || echo no-network");

        assertThat(result.stdout()).contains("no-network");
    }

    @Test
    void capsOutput() {
        SandboxPort.Result result = sandbox.run(request("run-" + UUID.randomUUID(), "yes x | head -c 5000", Duration.ofSeconds(60), 100));

        assertThat(result.truncated()).isTrue();
        assertThat(result.stdout()).hasSize(100);
    }

    @Test
    void aTimeoutKillsAndRemovesTheContainer() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        SandboxPort.Result result = sandbox.run(request(runId, "sleep 120", Duration.ofSeconds(3), 100));

        assertThat(result.timedOut()).isTrue();
        assertThat(run("docker", "ps", "-aq", "--filter", "label=pdlc.run=" + runId)).isBlank();
    }

    @Test
    void terminateRunKillsARunningCall() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        CompletableFuture<SandboxPort.Result> call = CompletableFuture.supplyAsync(
                () -> sandbox.run(request(runId, "echo started; sleep 120", Duration.ofSeconds(90), 100)));
        long deadline = System.currentTimeMillis() + 30_000;
        while (run("docker", "ps", "-q", "--filter", "label=pdlc.run=" + runId).isBlank() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }

        sandbox.terminateRun(runId);

        SandboxPort.Result result = call.get(30, TimeUnit.SECONDS);
        assertThat(result.exitCode()).isNotZero();
        assertThat(run("docker", "ps", "-aq", "--filter", "label=pdlc.run=" + runId)).isBlank();
    }

    @Test
    void itsOnlyWayOutIsTheProxyWithALiveCredentialToAnApprovedHost() throws Exception {
        assumeTrue(run("docker", "image", "inspect", "curlimages/curl:8.10.1") != null, "curlimages/curl:8.10.1 is not pulled");
        String base = run("docker", "create", "curlimages/curl:8.10.1").trim();
        run("docker", "commit", "--change", "ENTRYPOINT [\"sh\", \"-c\", \"eval \\\"$SCRIPT\\\"\"]", base, "pdlc-sandbox-test-curl:1");
        run("docker", "rm", base);
        String curlImage = run("docker", "image", "inspect", "--format", "{{.Id}}", "pdlc-sandbox-test-curl:1").trim();
        String gateway = run("docker", "network", "inspect", "bridge", "--format", "{{(index .IPAM.Config 0).Gateway}}").trim();

        com.sun.net.httpserver.HttpServer upstream = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("0.0.0.0", 0), 0);
        upstream.createContext("/", ex -> {
            byte[] body = "approved-upstream".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        int upstreamPort = upstream.getAddress().getPort();
        // "api.approved.test" stands for an approved public API; it resolves to this host for the test.
        ai.pdlc.core.platform.EgressPolicy egress = new ai.pdlc.core.platform.EgressPolicy(Set.of("api.approved.test", "api.other.test"),
                host -> {
                    try {
                        return new java.net.InetAddress[]{java.net.InetAddress.getByName("127.0.0.1")};
                    } catch (java.net.UnknownHostException e) {
                        return new java.net.InetAddress[0];
                    }
                });
        java.util.List<SandboxEgressProxy.Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        DockerSandbox networked = new DockerSandbox(new DockerSandbox.Config("docker", "runsc", "pdlc-sandbox-test-net",
                "pdlc-sandbox-test-relay", "pdlc-sandbox-test-relay", 3128));
        try (SandboxEgressProxy proxy = new SandboxEgressProxy("0.0.0.0", 0, egress::check, events::add)) {
            run("docker", "rm", "-f", "pdlc-sandbox-test-relay");
            networked.ensureNetwork("alpine/socat:1.8.0.0", gateway, proxy.port());
            String runId = "run-" + UUID.randomUUID();
            SandboxEgressProxy.Grant grant = proxy.issue(runId, runId + ":1:c", Set.of("api.approved.test:" + upstreamPort), Duration.ofMinutes(2));
            String proxyUrl = SandboxEgressProxy.proxyUrl(grant, "pdlc-sandbox-test-relay", 3128);
            String script = "curl -s -m 5 http://api.approved.test:" + upstreamPort + "/ ; echo;"
                    + " curl -s -m 5 -o /dev/null -w 'other=%{http_code}\\n' http://api.other.test:" + upstreamPort + "/ ;"
                    + " curl -s -m 3 --noproxy '*' http://" + gateway + ":" + upstreamPort + "/ || echo direct-blocked";

            SandboxPort.Result ok = networked.run(new SandboxPort.Request(runId, "ws-a", runId + ":1:c", curlImage, "{}",
                    Map.of("SCRIPT", script), 500, 128, Duration.ofSeconds(60), proxyUrl, 10_000));
            proxy.revoke(grant.token());
            SandboxPort.Result revoked = networked.run(new SandboxPort.Request(runId, "ws-a", runId + ":2:c", curlImage, "{}",
                    Map.of("SCRIPT", "curl -s -m 5 -o /dev/null -w 'code=%{http_code}' http://api.approved.test:" + upstreamPort + "/"),
                    500, 128, Duration.ofSeconds(60), proxyUrl, 10_000));

            assertThat(ok.stdout()).contains("approved-upstream").contains("other=403").contains("direct-blocked");
            assertThat(revoked.stdout()).contains("code=407");
            assertThat(events).anyMatch(e -> e.allowed() && e.host().equals("api.approved.test"))
                    .anyMatch(e -> !e.allowed() && e.host().equals("api.other.test"));
        } finally {
            upstream.stop(0);
            run("docker", "rm", "-f", "pdlc-sandbox-test-relay");
            run("docker", "network", "rm", "pdlc-sandbox-test-net");
        }
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
