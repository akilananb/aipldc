package ai.pdlc.agents.config;

import ai.pdlc.adapters.sandbox.DockerSandbox;
import ai.pdlc.adapters.sandbox.KubernetesApi;
import ai.pdlc.adapters.sandbox.KubernetesJobSandbox;
import ai.pdlc.adapters.sandbox.SandboxEgressProxy;
import ai.pdlc.agents.platform.SandboxToolRunner;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.port.SandboxPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sandbox tools (docs/phase-2-execution-spec.md slice 2.4). {@code pdlc.sandbox.provider}:
 * <ul>
 *   <li>{@code none} (default) - every sandbox tool call is refused;</li>
 *   <li>{@code docker} - local development: containers under {@code pdlc.sandbox.runtime}
 *       (gVisor {@code runsc}) on an internal network whose only way out is a relay to the egress
 *       proxy this worker runs;</li>
 *   <li>{@code kubernetes} - a Job per call under the {@code runtimeClassName}
 *       {@code pdlc.sandbox.runtime} in {@code pdlc.sandbox.kubernetes.namespace}, with the
 *       default-deny NetworkPolicy allowing only this worker's egress proxy.</li>
 * </ul>
 * A provider without a runtime refuses every call: untrusted packages never share the host kernel.
 */
@Configuration
public class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    @Bean
    SandboxToolRunner sandboxToolRunner(
            @Value("${pdlc.sandbox.provider:none}") String provider,
            @Value("${pdlc.sandbox.runtime:}") String runtime,
            @Value("${pdlc.sandbox.proxy.bind-host:0.0.0.0}") String proxyBindHost,
            @Value("${pdlc.sandbox.proxy.port:3128}") int proxyPort,
            @Value("${pdlc.sandbox.proxy.advertised-host:}") String advertisedHost,
            @Value("${pdlc.sandbox.docker.binary:docker}") String docker,
            @Value("${pdlc.sandbox.docker.network:pdlc-sandbox}") String dockerNetwork,
            @Value("${pdlc.sandbox.docker.relay-name:pdlc-sandbox-relay}") String relayName,
            @Value("${pdlc.sandbox.docker.relay-port:3128}") int relayPort,
            @Value("${pdlc.sandbox.docker.relay-image:alpine/socat:1.8.0.0}") String relayImage,
            @Value("${pdlc.sandbox.docker.proxy-host-address:172.17.0.1}") String proxyHostAddress,
            @Value("${pdlc.sandbox.kubernetes.namespace:pdlc-sandbox}") String namespace,
            @Value("${pdlc.sandbox.kubernetes.proxy-namespace:pdlc}") String proxyNamespace,
            @Value("${pdlc.sandbox.kubernetes.proxy-labels:app=agents}") String proxyLabels,
            EgressPolicy egress) throws IOException {
        if ("none".equals(provider)) {
            log.info("Sandbox tools are disabled (pdlc.sandbox.provider=none); sandbox tool calls are refused");
            return new SandboxToolRunner(null, null, null, 0);
        }
        AtomicReference<SandboxToolRunner> runner = new AtomicReference<>();
        SandboxEgressProxy proxy = new SandboxEgressProxy(proxyBindHost, proxyPort, egress::check, e -> {
            SandboxToolRunner r = runner.get();
            if (r != null) {
                r.onEgress(e);
            }
        });
        SandboxPort port;
        String host;
        int advertisedPort;
        switch (provider) {
            case "docker" -> {
                DockerSandbox sandbox = new DockerSandbox(new DockerSandbox.Config(docker, blankToNull(runtime), dockerNetwork,
                        relayName, relayName, relayPort));
                if (sandbox.isolation() != null) {
                    sandbox.ensureNetwork(relayImage, proxyHostAddress, proxy.port());
                }
                port = sandbox;
                host = advertisedHost.isBlank() ? relayName : advertisedHost;
                advertisedPort = relayPort;
            }
            case "kubernetes" -> {
                KubernetesJobSandbox sandbox = new KubernetesJobSandbox(KubernetesApi.inCluster(), new KubernetesJobSandbox.Config(
                        namespace, blankToNull(runtime), proxyNamespace, labels(proxyLabels), proxy.port(), 1000));
                if (sandbox.isolation() != null) {
                    try {
                        sandbox.ensureNetworkPolicy();
                    } catch (IllegalStateException e) {
                        log.warn("Could not ensure the sandbox NetworkPolicy: {}", e.getMessage());
                    }
                }
                if (advertisedHost.isBlank()) {
                    throw new IllegalStateException("pdlc.sandbox.proxy.advertised-host is required for the kubernetes provider");
                }
                port = sandbox;
                host = advertisedHost;
                advertisedPort = proxy.port();
            }
            default -> throw new IllegalStateException("pdlc.sandbox.provider must be none, docker or kubernetes");
        }
        runner.set(new SandboxToolRunner(port, proxy, host, advertisedPort));
        log.info("Sandbox tools run on {} ({}); egress proxy on port {}", provider,
                port.isolation() == null ? "NO isolation runtime - every call is refused" : port.isolation(), proxy.port());
        return runner.get();
    }

    private static Map<String, String> labels(String csv) {
        Map<String, String> labels = new LinkedHashMap<>();
        Arrays.stream(csv.split(",")).map(String::trim).filter(s -> s.contains("=")).forEach(s ->
                labels.put(s.substring(0, s.indexOf('=')), s.substring(s.indexOf('=') + 1)));
        return labels;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
