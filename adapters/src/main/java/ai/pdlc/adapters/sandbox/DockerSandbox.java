package ai.pdlc.adapters.sandbox;

import ai.pdlc.core.port.SandboxPort;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link SandboxPort} on a local Docker engine for development (docs/phase-2-execution-spec.md
 * slice 2.4), mirroring the Kubernetes rules: the configured isolation runtime (gVisor
 * {@code runsc}), non-root, read-only root filesystem, all capabilities dropped, no new privileges,
 * CPU/memory/pids limits, size-capped tmpfs {@code /workspace} and {@code /tmp}, no mounts, and an
 * {@code --internal} network whose only way out is a relay to the egress proxy. Secrets and input
 * go through an owner-only env file, never the command line. The container is removed when the
 * call ends, times out or its run is cancelled.
 */
public final class DockerSandbox implements SandboxPort {

    /** Settings; {@code runtime} null = no isolation runtime, and every run is refused. */
    public record Config(String docker, String runtime, String network, String relayName, String relayHost, int relayPort) {
    }

    private final Config config;

    public DockerSandbox(Config config) {
        this.config = config;
    }

    @Override
    public String isolation() {
        return config.runtime() == null || config.runtime().isBlank() ? null : "docker runtime " + config.runtime();
    }

    @Override
    public Result run(Request r) {
        if (isolation() == null) {
            throw new IllegalStateException("no isolation runtime is configured; untrusted packages are refused");
        }
        String name = "pdlc-sbx-" + HexFormat.of().formatHex(sha(r.callKey())).substring(0, 20);
        Path envFile = null;
        try {
            envFile = Files.createTempFile("pdlc-sbx-", ".env", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Map<String, String> env = new LinkedHashMap<>(r.env());
            env.put("PDLC_INPUT", r.inputJson());
            if (r.proxyUrl() != null) {
                for (String k : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")) {
                    env.put(k, r.proxyUrl());
                }
            }
            StringBuilder lines = new StringBuilder();
            env.forEach((k, v) -> lines.append(k).append('=').append(v.replace("\n", " ")).append('\n'));
            Files.writeString(envFile, lines);

            List<String> cmd = new ArrayList<>(List.of(config.docker(), "run", "--name", name,
                    "--label", "pdlc.sandbox=true", "--label", "pdlc.run=" + r.runId(), "--label", "pdlc.workspace=" + r.workspaceId(),
                    "--runtime", config.runtime(), "--read-only", "--user", "65534:65534", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges", "--pids-limit", "128",
                    "--memory", r.memoryMb() + "m", "--memory-swap", r.memoryMb() + "m",
                    "--cpus", String.valueOf(r.cpuMillis() / 1000.0),
                    "--tmpfs", "/workspace:rw,size=64m,mode=1777", "--tmpfs", "/tmp:rw,size=16m,mode=1777",
                    "--workdir", "/workspace", "--env-file", envFile.toString(),
                    "--network", r.proxyUrl() == null ? "none" : config.network()));
            if (r.proxyUrl() != null) {
                // Docker's embedded DNS does not work under gVisor; the relay is the only name a
                // sandbox needs (targets are resolved by the proxy), so pin it and nothing else.
                String relayIp = relayAddress();
                if (relayIp == null) {
                    return new Result(-1, "", false, false, "the sandbox egress relay is not running");
                }
                cmd.addAll(List.of("--add-host", config.relayHost() + ":" + relayIp, "--dns", "127.0.0.1"));
            }
            cmd.add(r.imageRef());
            Process process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream(), r.maxOutputBytes() + 1));
            CompletableFuture.runAsync(() -> read(process.getErrorStream(), 64_000));
            boolean finished = process.waitFor(r.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                remove(name);
                process.destroyForcibly();
            }
            byte[] out = stdout.get(10, TimeUnit.SECONDS);
            boolean truncated = out.length > r.maxOutputBytes();
            String text = new String(out, 0, Math.min(out.length, r.maxOutputBytes()), StandardCharsets.UTF_8);
            return new Result(finished ? process.exitValue() : -1, text, truncated, !finished, null);
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return new Result(-1, "", false, false, "the sandbox could not be run (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", false, false, "interrupted");
        } finally {
            remove(name);
            if (envFile != null) {
                try {
                    Files.deleteIfExists(envFile);
                } catch (IOException ignored) {
                    // temp file; best effort
                }
            }
        }
    }

    @Override
    public void terminateRun(String runId) {
        String ids = exec(List.of(config.docker(), "ps", "-aq", "--filter", "label=pdlc.run=" + runId)).trim();
        if (!ids.isEmpty()) {
            List<String> cmd = new ArrayList<>(List.of(config.docker(), "rm", "-f"));
            cmd.addAll(List.of(ids.split("\\s+")));
            exec(cmd);
        }
    }

    /**
     * Creates the {@code --internal} sandbox network and the relay container bridging it to the
     * egress proxy on the host - the only route out. Idempotent.
     */
    public void ensureNetwork(String relayImage, String proxyHostAddress, int proxyPort) {
        if (exec(List.of(config.docker(), "network", "inspect", config.network())).isBlank()) {
            exec(List.of(config.docker(), "network", "create", "--internal", config.network()));
        }
        if (exec(List.of(config.docker(), "inspect", config.relayName())).isBlank()) {
            exec(List.of(config.docker(), "run", "-d", "--name", config.relayName(), "--restart", "unless-stopped",
                    "--read-only", "--cap-drop", "ALL", "--add-host", "proxy-host:" + proxyHostAddress,
                    relayImage, "TCP-LISTEN:" + config.relayPort() + ",fork,reuseaddr", "TCP:proxy-host:" + proxyPort));
            exec(List.of(config.docker(), "network", "connect", config.network(), config.relayName()));
        }
    }

    private String relayAddress() {
        String ip = exec(List.of(config.docker(), "inspect", "--format",
                "{{(index .NetworkSettings.Networks \"" + config.network() + "\").IPAddress}}", config.relayName())).trim();
        return ip.isEmpty() || ip.startsWith("<") ? null : ip;
    }

    private void remove(String name) {
        exec(List.of(config.docker(), "rm", "-f", name));
    }

    private static String exec(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            byte[] out = read(p.getInputStream(), 1_000_000);
            read(p.getErrorStream(), 64_000);
            p.waitFor(60, TimeUnit.SECONDS);
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : "";
        } catch (IOException | IllegalThreadStateException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static byte[] read(InputStream in, int max) {
        try (in) {
            byte[] bytes = in.readNBytes(max);
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return bytes;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] sha(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
