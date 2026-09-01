package ai.pdlc.adapters.localci;

import ai.pdlc.core.domain.RunRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises {@link LocalCiAdapter} against a real git repo with a real (tiny) node test suite -
 * no mocking of git or {@code npm test}. */
class LocalCiAdapterContractTest {

    @TempDir
    Path tmp;

    private Path repoPath;
    private LocalCiAdapter ci;

    @BeforeEach
    void initRepo() throws IOException, InterruptedException {
        repoPath = tmp.resolve("repo");
        Files.createDirectories(repoPath.resolve("test"));
        sh(repoPath, "git", "init", "-q", "-b", "main");
        sh(repoPath, "git", "config", "user.email", "seed@test");
        sh(repoPath, "git", "config", "user.name", "seed");
        Files.writeString(repoPath.resolve("package.json"),
                "{\"name\":\"fixture\",\"scripts\":{\"test\":\"node --test\"}}");
        Files.writeString(repoPath.resolve("test/a.test.js"),
                "const test = require('node:test');\ntest('passes', () => {});\n");
        sh(repoPath, "git", "add", "-A");
        sh(repoPath, "git", "commit", "-q", "-m", "seed");
        ci = new LocalCiAdapter(repoPath.toString());
    }

    private static void sh(Path cwd, String... args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", args) + "\n" + out);
        }
    }

    @Test
    void runVerifyOnAGreenBranchSucceeds() {
        RunRef ref = ci.runVerify("main");
        assertThat(ci.getRun(ref.id()).success()).isTrue();
        assertThat(ci.getRun(ref.id()).state()).isEqualTo("succeeded");
    }

    @Test
    void runVerifyOnARedBranchThrows() throws IOException, InterruptedException {
        sh(repoPath, "git", "checkout", "-q", "-b", "broken");
        Files.writeString(repoPath.resolve("test/a.test.js"),
                "const test = require('node:test'); const assert = require('node:assert');\n"
                        + "test('fails', () => { assert.equal(1, 2); });\n");
        sh(repoPath, "git", "commit", "-q", "-am", "break it");
        sh(repoPath, "git", "checkout", "-q", "main");

        assertThatThrownBy(() -> ci.runVerify("broken")).isInstanceOf(LocalCiAdapterException.class);
    }

    @Test
    void runDeployOnAGreenBranchWritesAMarkerAndReturnsAnArtifact() {
        RunRef ref = ci.runDeploy("production", "R-2026-08-31-01", Map.of("branch", "main"));

        assertThat(ci.getRun(ref.id()).success()).isTrue();
        List<ai.pdlc.core.domain.ArtifactRef> artifacts = ci.getArtifacts(ref.id());
        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0).name()).isEqualTo("deployment-marker");

        Path marker = repoPath.resolve(".git/pdlc-deploys/R-2026-08-31-01.json");
        assertThat(marker).exists();
    }

    @Test
    void runDeployOnARedBranchDoesNotWriteAMarker() throws IOException, InterruptedException {
        sh(repoPath, "git", "checkout", "-q", "-b", "broken2");
        Files.writeString(repoPath.resolve("test/a.test.js"),
                "const test = require('node:test'); const assert = require('node:assert');\n"
                        + "test('fails', () => { assert.equal(1, 2); });\n");
        sh(repoPath, "git", "commit", "-q", "-am", "break it");
        sh(repoPath, "git", "checkout", "-q", "main");

        assertThatThrownBy(() -> ci.runDeploy("production", "R-broken", Map.of("branch", "broken2")))
                .isInstanceOf(LocalCiAdapterException.class);
        assertThat(repoPath.resolve(".git/pdlc-deploys/R-broken.json")).doesNotExist();
    }

    @Test
    void getRunForUnknownRefThrows() {
        assertThatThrownBy(() -> ci.getRun("nonexistent")).isInstanceOf(LocalCiAdapterException.class);
    }

    @Test
    void onWebhookIsUnsupported() {
        assertThatThrownBy(() -> ci.onWebhook("local", Map.of())).isInstanceOf(UnsupportedOperationException.class);
    }
}
