package ai.pdlc.core.port;

import java.time.Duration;
import java.util.Map;

/**
 * Isolated execution of enterprise-approved container images (docs/phase-2-execution-spec.md slice
 * 2.4). Adapters: {@code KubernetesJobSandbox} (a Job per call under a gVisor/Kata
 * {@code runtimeClassName}, default-deny NetworkPolicy) and {@code DockerSandbox} (local dev, same
 * rules under {@code --runtime=runsc}). Every run is non-root with a read-only root filesystem, a
 * fresh per-call scratch workspace, resource and time limits, only the given environment, no host
 * mounts and no container-runtime socket. Its only network path is the egress proxy at
 * {@code proxyUrl}, whose short-lived credential is embedded there and revoked by the caller.
 */
public interface SandboxPort {

    /**
     * One call. {@code callKey} names it uniquely (run:turn:callId); {@code inputJson} reaches the
     * container as {@code PDLC_INPUT}; {@code proxyUrl} null means no network at all.
     */
    record Request(String runId, String workspaceId, String callKey, String imageRef, String inputJson,
                   Map<String, String> env, int cpuMillis, int memoryMb, Duration timeout, String proxyUrl,
                   int maxOutputBytes) {

        @Override
        public String toString() {
            return "Request[callKey=" + callKey + ", imageRef=" + imageRef + "]";
        }
    }

    /** {@code error} reports an infrastructure failure (the package may not have run); stdout is capped. */
    record Result(int exitCode, String stdout, boolean truncated, boolean timedOut, String error) {
    }

    /**
     * Runs to completion or {@code timeout}, then removes every trace of the call (container/Job,
     * its process tree, its workspace). Throws {@link IllegalStateException} when no isolation
     * runtime is configured - untrusted packages never fall back to the host kernel.
     */
    Result run(Request request);

    /** Kills and removes everything still running for {@code runId} (run cancellation). */
    void terminateRun(String runId);

    /** Human-readable isolation in force, e.g. {@code runtimeClass gvisor}; null when none is configured. */
    String isolation();
}
