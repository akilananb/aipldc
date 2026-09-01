package ai.pdlc.adapters.localci;

import ai.pdlc.core.domain.ArtifactRef;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.RunRef;
import ai.pdlc.core.domain.RunStatus;
import ai.pdlc.core.port.CiPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-git, real-{@code npm test} {@link CiPort} — build-order phase 4's {@code local-ci}
 * provider. {@code runVerify} checks the given ref out into a throwaway worktree and runs the
 * target repo's own verifier (matches build-worker's verifier, {@code docs/agent-playbook.md} §5
 * "called ... by CI on every PR push"). {@code runDeploy} re-verifies, then (only if green) writes
 * a deployment-marker file under {@code <repo>/.git/pdlc-deploys/} — the pilot's stand-in for a
 * real deploy target, since there is no cloud infra to actually roll out to.
 */
public final class LocalCiAdapter implements CiPort {

    private final Path repoPath;
    private final Path deploysDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RunStatus> runs = new ConcurrentHashMap<>();
    private final Map<String, List<ArtifactRef>> artifacts = new ConcurrentHashMap<>();

    public LocalCiAdapter(String repoPath) {
        this.repoPath = Path.of(repoPath);
        if (!Files.isDirectory(this.repoPath.resolve(".git"))) {
            throw new LocalCiAdapterException("Not a git repository: " + repoPath);
        }
        this.deploysDir = this.repoPath.resolve(".git").resolve("pdlc-deploys");
        try {
            Files.createDirectories(deploysDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public RunRef runVerify(String branchOrPr) {
        String runId = UUID.randomUUID().toString();
        VerifyOutcome outcome = verify(branchOrPr);
        runs.put(runId, new RunStatus(outcome.success ? "succeeded" : "failed", outcome.success));
        artifacts.put(runId, List.of());
        if (!outcome.success) {
            throw new LocalCiAdapterException("runVerify(" + branchOrPr + ") failed: " + outcome.output);
        }
        return new RunRef(runId);
    }

    @Override
    public RunRef runDeploy(String env, String releaseId, Map<String, Object> params) {
        String branch = String.valueOf(params.getOrDefault("branch", "main"));
        String runId = UUID.randomUUID().toString();

        VerifyOutcome outcome = verify(branch);
        if (!outcome.success) {
            runs.put(runId, new RunStatus("failed", false));
            artifacts.put(runId, List.of());
            throw new LocalCiAdapterException("runDeploy(" + env + ", " + releaseId + ") aborted: verify failed on "
                    + branch + ": " + outcome.output);
        }

        String commitSha = revParse(branch);
        Path marker = deploysDir.resolve(releaseId + ".json");
        ObjectNode record = mapper.createObjectNode();
        record.put("env", env);
        record.put("releaseId", releaseId);
        record.put("branch", branch);
        record.put("commitSha", commitSha);
        record.put("deployedAt", Instant.now().toString());
        try {
            Files.writeString(marker, record.toPrettyString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        runs.put(runId, new RunStatus("succeeded", true));
        artifacts.put(runId, List.of(new ArtifactRef("deployment-marker", marker.toUri().toString())));
        return new RunRef(runId);
    }

    @Override
    public RunStatus getRun(String ref) {
        RunStatus status = runs.get(ref);
        if (status == null) {
            throw new LocalCiAdapterException("No such run: " + ref);
        }
        return status;
    }

    @Override
    public List<ArtifactRef> getArtifacts(String ref) {
        return artifacts.getOrDefault(ref, List.of());
    }

    @Override
    public CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent) {
        throw new UnsupportedOperationException(
                "local-ci has no webhook source; the pilot's local profile drives verify/deploy"
                        + " directly through the workflow's own CiPort calls, never inbound webhooks");
    }

    // -- git plumbing (mirrors LocalGitRepoAdapter's shell-out pattern) -------------------------

    private record VerifyOutcome(boolean success, String output) {
    }

    private VerifyOutcome verify(String ref) {
        Path worktree;
        try {
            worktree = Files.createTempDirectory("pdlc-ci-verify-");
            Files.delete(worktree);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            git(repoPath, List.of("worktree", "add", "--detach", worktree.toString(), ref));
            ProcessResult npmTest = run(worktree, List.of("npm", "test"));
            return new VerifyOutcome(npmTest.exitCode == 0, npmTest.output);
        } finally {
            try {
                git(repoPath, List.of("worktree", "remove", "--force", worktree.toString()));
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }
    }

    private String revParse(String ref) {
        return run(repoPath, List.of("git", "rev-parse", ref)).output.strip();
    }

    private void git(Path cwd, List<String> args) {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", cwd.toString()));
        command.addAll(args);
        ProcessResult result = run(cwd, command);
        if (result.exitCode != 0) {
            throw new LocalCiAdapterException("git " + String.join(" ", args) + " failed: " + result.output);
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private ProcessResult run(Path cwd, List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new ProcessResult(exit, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalCiAdapterException("interrupted running " + command, e);
        }
    }
}
