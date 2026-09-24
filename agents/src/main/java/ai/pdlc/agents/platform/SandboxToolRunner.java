package ai.pdlc.agents.platform;

import ai.pdlc.adapters.sandbox.SandboxEgressProxy;
import ai.pdlc.core.port.SandboxPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Runs one sandbox tool call (docs/phase-2-execution-spec.md slice 2.4) for {@link ToolExecutor}:
 * mints the call's egress-proxy credential scoped to the image's approved hosts, runs the image
 * through the {@link SandboxPort}, and revokes the credential the moment the call returns - however
 * it ends. {@link #terminateRun} is the cancellation path: it revokes every credential of the run
 * and kills whatever is still running for it.
 *
 * <p>A runner without a port (or whose port reports no isolation) refuses every call: untrusted
 * packages never run on the host kernel.
 */
public class SandboxToolRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SandboxToolRunner.class);

    /** What the executor needs back: the port's result, the egress decisions, and the token to redact. */
    public record Run(SandboxPort.Result result, List<SandboxEgressProxy.Event> egress, String tokenToRedact) {
    }

    private final SandboxPort port;
    private final SandboxEgressProxy proxy;
    private final String advertisedProxyHost;
    private final int advertisedProxyPort;
    private final Map<String, List<SandboxEgressProxy.Event>> events = new ConcurrentHashMap<>();

    /** {@code port} null = sandboxing is not configured; {@code proxy} null = packages get no network at all. */
    public SandboxToolRunner(SandboxPort port, SandboxEgressProxy proxy, String advertisedProxyHost, int advertisedProxyPort) {
        this.port = port;
        this.proxy = proxy;
        this.advertisedProxyHost = advertisedProxyHost;
        this.advertisedProxyPort = advertisedProxyPort;
    }

    /** The proxy's decision sink (wired by config); decisions are kept per call for the trace. */
    public void onEgress(SandboxEgressProxy.Event event) {
        List<SandboxEgressProxy.Event> list = events.get(event.callKey());
        if (list != null) {
            list.add(event);
        }
        log.info("Sandbox egress {} {}:{} -> {}{}", event.callKey(), event.host(), event.port(),
                event.allowed() ? "allowed" : "denied", event.reason() == null ? "" : " (" + event.reason() + ")");
    }

    /** Null when calls are refused. */
    public String isolation() {
        return port == null ? null : port.isolation();
    }

    public Run run(String runId, String workspaceId, String callKey, ToolStore.SandboxImage image, String inputJson,
                   Duration timeout, int maxOutputBytes) {
        List<String> hosts = ToolStore.readHosts(image.egressHostsJson());
        SandboxEgressProxy.Grant grant = proxy == null || hosts.isEmpty() ? null
                : proxy.issue(runId, callKey, Set.copyOf(hosts), timeout.plusSeconds(30));
        events.put(callKey, new CopyOnWriteArrayList<>());
        try {
            SandboxPort.Result result = port.run(new SandboxPort.Request(runId, workspaceId, callKey, image.imageRef(), inputJson,
                    Map.of(), image.cpuMillis(), image.memoryMb(), timeout,
                    grant == null ? null : SandboxEgressProxy.proxyUrl(grant, advertisedProxyHost, advertisedProxyPort),
                    maxOutputBytes));
            return new Run(result, List.copyOf(events.getOrDefault(callKey, List.of())), grant == null ? null : grant.token());
        } finally {
            if (grant != null) {
                proxy.revoke(grant.token());
            }
            events.remove(callKey);
        }
    }

    /** Cancellation: the run's credentials stop working first, then its containers/Jobs are killed. */
    public void terminateRun(String runId) {
        if (proxy != null) {
            proxy.revokeRun(runId);
        }
        if (port != null) {
            try {
                port.terminateRun(runId);
            } catch (RuntimeException e) {
                log.warn("Terminating sandbox calls of run {} failed: {}", runId, e.toString());
            }
        }
    }

    /** Stops the egress proxy (every credential dies with it). */
    @Override
    public void close() throws java.io.IOException {
        if (proxy != null) {
            proxy.close();
        }
    }

    static String summary(List<SandboxEgressProxy.Event> egress) {
        if (egress.isEmpty()) {
            return null;
        }
        return "egress: " + egress.stream()
                .map(e -> e.host() + ":" + e.port() + (e.allowed() ? " allowed" : " denied"))
                .collect(Collectors.joining(", "));
    }
}
