package ai.pdlc.adapters.sandbox;

import ai.pdlc.core.port.SandboxPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link SandboxPort} as one Kubernetes Job per call (docs/phase-2-execution-spec.md slice 2.4).
 *
 * <ul>
 *   <li>{@code runtimeClassName} from config (gVisor or Kata); without one every run is refused -
 *       untrusted packages never share the host kernel.</li>
 *   <li>Non-root ({@code 65534}), {@code runAsNonRoot}, read-only root filesystem, no privilege
 *       escalation, all capabilities dropped, {@code RuntimeDefault} seccomp, no service-account
 *       token, no service links, no host namespaces or mounts: the only volumes are size-limited
 *       {@code emptyDir}s for {@code /workspace} and {@code /tmp}.</li>
 *   <li>CPU/memory requests = limits, {@code activeDeadlineSeconds}, no retries.</li>
 *   <li>Input and the proxy credential travel in a per-call Secret (not the Job spec), deleted
 *       with the Job.</li>
 *   <li>{@link #ensureNetworkPolicy} installs a default-deny policy for every sandbox pod whose
 *       only egress is the platform egress proxy (and cluster DNS).</li>
 * </ul>
 * Timeout and cancellation delete the Job with foreground propagation, which kills the pod and its
 * whole process tree; the caller revokes the proxy credential at the same time.
 */
public final class KubernetesJobSandbox implements SandboxPort {

    /** {@code proxyNamespace}/{@code proxyPodLabels} select the egress proxy pods the policy allows. */
    public record Config(String namespace, String runtimeClass, String proxyNamespace, Map<String, String> proxyPodLabels,
                         int proxyPort, long pollMillis) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KubernetesApi api;
    private final Config config;
    private final Clock clock;

    public KubernetesJobSandbox(KubernetesApi api, Config config) {
        this(api, config, Clock.systemUTC());
    }

    KubernetesJobSandbox(KubernetesApi api, Config config, Clock clock) {
        this.api = api;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public String isolation() {
        return config.runtimeClass() == null || config.runtimeClass().isBlank() ? null : "runtimeClass " + config.runtimeClass();
    }

    @Override
    public Result run(Request r) {
        if (isolation() == null) {
            throw new IllegalStateException("no isolation runtimeClass is configured; untrusted packages are refused");
        }
        String name = "pdlc-sbx-" + HexFormat.of().formatHex(sha(r.callKey())).substring(0, 20);
        String ns = config.namespace();
        try {
            api.post("/api/v1/namespaces/" + ns + "/secrets", secret(name, r));
            api.post("/apis/batch/v1/namespaces/" + ns + "/jobs", job(name, r));
            Instant deadline = clock.instant().plus(r.timeout());
            while (clock.instant().isBefore(deadline)) {
                JsonNode job = api.get("/apis/batch/v1/namespaces/" + ns + "/jobs/" + name);
                JsonNode status = job == null ? null : job.path("status");
                if (job == null) {
                    return new Result(-1, "", false, false, "the sandbox Job disappeared");
                }
                if (status.path("succeeded").asInt() > 0 || status.path("failed").asInt() > 0) {
                    return collect(name, r, status.path("succeeded").asInt() > 0);
                }
                Thread.sleep(config.pollMillis());
            }
            return new Result(-1, "", false, true, null);
        } catch (IllegalStateException e) {
            return new Result(-1, "", false, false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", false, false, "interrupted");
        } finally {
            quietly(() -> api.delete("/apis/batch/v1/namespaces/" + ns + "/jobs/" + name));
            quietly(() -> api.delete("/api/v1/namespaces/" + ns + "/secrets/" + name));
        }
    }

    /** Cleanup must not mask the call's result; the Job's TTL and {@link #terminateRun} catch leftovers. */
    private static void quietly(Runnable delete) {
        try {
            delete.run();
        } catch (IllegalStateException ignored) {
            // API unreachable or refused; ttlSecondsAfterFinished removes the Job
        }
    }

    private Result collect(String name, Request r, boolean succeeded) {
        JsonNode pods = api.get("/api/v1/namespaces/" + config.namespace() + "/pods?labelSelector="
                + KubernetesApi.label("job-name=" + name));
        JsonNode pod = pods == null ? null : pods.path("items").path(0);
        if (pod == null || pod.isMissingNode()) {
            return new Result(succeeded ? 0 : 1, "", false, false, null);
        }
        int exit = pod.path("status").path("containerStatuses").path(0).path("state").path("terminated").path("exitCode")
                .asInt(succeeded ? 0 : 1);
        String log = api.getText("/api/v1/namespaces/" + config.namespace() + "/pods/" + pod.path("metadata").path("name").asText()
                + "/log?container=package&limitBytes=" + (r.maxOutputBytes() + 1));
        boolean truncated = log.getBytes(StandardCharsets.UTF_8).length > r.maxOutputBytes();
        return new Result(exit, truncated ? log.substring(0, Math.min(log.length(), r.maxOutputBytes())) : log, truncated, false, null);
    }

    @Override
    public void terminateRun(String runId) {
        String selector = KubernetesApi.label("pdlc.run=" + runId);
        api.delete("/apis/batch/v1/namespaces/" + config.namespace() + "/jobs?labelSelector=" + selector);
        api.delete("/api/v1/namespaces/" + config.namespace() + "/secrets?labelSelector=" + selector);
    }

    /** Default-deny for sandbox pods: no ingress; egress only to the egress proxy and cluster DNS. Idempotent. */
    public void ensureNetworkPolicy() {
        String path = "/apis/networking.k8s.io/v1/namespaces/" + config.namespace() + "/networkpolicies";
        if (api.get(path + "/pdlc-sandbox-egress") != null) {
            return;
        }
        ObjectNode policy = JSON.createObjectNode().put("apiVersion", "networking.k8s.io/v1").put("kind", "NetworkPolicy");
        policy.putObject("metadata").put("name", "pdlc-sandbox-egress");
        ObjectNode spec = policy.putObject("spec");
        spec.putObject("podSelector").putObject("matchLabels").put("pdlc.sandbox", "true");
        spec.putArray("policyTypes").add("Ingress").add("Egress");
        spec.putArray("ingress");
        ArrayNode egress = spec.putArray("egress");
        ObjectNode toProxy = egress.addObject();
        ObjectNode peer = toProxy.putArray("to").addObject();
        peer.putObject("namespaceSelector").putObject("matchLabels").put("kubernetes.io/metadata.name", config.proxyNamespace());
        ObjectNode proxyLabels = peer.putObject("podSelector").putObject("matchLabels");
        config.proxyPodLabels().forEach(proxyLabels::put);
        toProxy.putArray("ports").addObject().put("protocol", "TCP").put("port", config.proxyPort());
        ObjectNode dns = egress.addObject();
        ObjectNode dnsPeer = dns.putArray("to").addObject();
        dnsPeer.putObject("namespaceSelector").putObject("matchLabels").put("kubernetes.io/metadata.name", "kube-system");
        dnsPeer.putObject("podSelector").putObject("matchLabels").put("k8s-app", "kube-dns");
        ArrayNode dnsPorts = dns.putArray("ports");
        dnsPorts.addObject().put("protocol", "UDP").put("port", 53);
        dnsPorts.addObject().put("protocol", "TCP").put("port", 53);
        api.post(path, policy);
    }

    private ObjectNode secret(String name, Request r) {
        ObjectNode secret = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Secret");
        labels(secret.putObject("metadata").put("name", name), r);
        secret.put("type", "Opaque");
        ObjectNode data = secret.putObject("data");
        Map<String, String> env = new LinkedHashMap<>(r.env());
        env.put("PDLC_INPUT", r.inputJson());
        if (r.proxyUrl() != null) {
            for (String k : new String[]{"HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"}) {
                env.put(k, r.proxyUrl());
            }
        }
        env.forEach((k, v) -> data.put(k, Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))));
        return secret;
    }

    ObjectNode job(String name, Request r) {
        ObjectNode job = JSON.createObjectNode().put("apiVersion", "batch/v1").put("kind", "Job");
        labels(job.putObject("metadata").put("name", name), r);
        ObjectNode spec = job.putObject("spec");
        spec.put("backoffLimit", 0).put("activeDeadlineSeconds", Math.max(1, r.timeout().toSeconds()))
                .put("ttlSecondsAfterFinished", 300);
        ObjectNode template = spec.putObject("template");
        labels(template.putObject("metadata"), r);
        ObjectNode pod = template.putObject("spec");
        pod.put("runtimeClassName", config.runtimeClass()).put("restartPolicy", "Never")
                .put("automountServiceAccountToken", false).put("enableServiceLinks", false)
                .put("hostNetwork", false).put("hostPID", false).put("hostIPC", false);
        ObjectNode podSecurity = pod.putObject("securityContext").put("runAsNonRoot", true).put("runAsUser", 65534)
                .put("runAsGroup", 65534).put("fsGroup", 65534);
        podSecurity.putObject("seccompProfile").put("type", "RuntimeDefault");
        ObjectNode c = pod.putArray("containers").addObject().put("name", "package").put("image", r.imageRef())
                .put("imagePullPolicy", "IfNotPresent").put("workingDir", "/workspace");
        c.putArray("envFrom").addObject().putObject("secretRef").put("name", name);
        ObjectNode cs = c.putObject("securityContext").put("allowPrivilegeEscalation", false).put("readOnlyRootFilesystem", true)
                .put("privileged", false);
        cs.putObject("capabilities").putArray("drop").add("ALL");
        ObjectNode resources = c.putObject("resources");
        for (String kind : new String[]{"limits", "requests"}) {
            resources.putObject(kind).put("cpu", r.cpuMillis() + "m").put("memory", r.memoryMb() + "Mi")
                    .put("ephemeral-storage", "128Mi");
        }
        ArrayNode mounts = c.putArray("volumeMounts");
        mounts.addObject().put("name", "workspace").put("mountPath", "/workspace");
        mounts.addObject().put("name", "tmp").put("mountPath", "/tmp");
        ArrayNode volumes = pod.putArray("volumes");
        volumes.addObject().put("name", "workspace").putObject("emptyDir").put("sizeLimit", "64Mi");
        volumes.addObject().put("name", "tmp").putObject("emptyDir").put("sizeLimit", "16Mi");
        return job;
    }

    private static void labels(ObjectNode metadata, Request r) {
        metadata.putObject("labels").put("pdlc.sandbox", "true").put("pdlc.run", r.runId()).put("pdlc.workspace", r.workspaceId());
    }

    private static byte[] sha(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
