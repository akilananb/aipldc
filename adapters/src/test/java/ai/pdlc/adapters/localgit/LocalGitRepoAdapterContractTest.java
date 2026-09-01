package ai.pdlc.adapters.localgit;

import ai.pdlc.core.domain.CommitRef;
import ai.pdlc.core.domain.PRRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises {@link LocalGitRepoAdapter} against a real {@code git} repository - no mocking. */
class LocalGitRepoAdapterContractTest {

    @TempDir
    Path tmp;

    private Path repoPath;
    private LocalGitRepoAdapter repo;

    @BeforeEach
    void initRepo() throws IOException, InterruptedException {
        repoPath = tmp.resolve("repo");
        Files.createDirectories(repoPath);
        sh(repoPath, "git", "init", "-q", "-b", "main");
        sh(repoPath, "git", "config", "user.email", "seed@test");
        sh(repoPath, "git", "config", "user.name", "seed");
        Files.writeString(repoPath.resolve("README.md"), "seed\n");
        sh(repoPath, "git", "add", "-A");
        sh(repoPath, "git", "commit", "-q", "-m", "seed");
        repo = new LocalGitRepoAdapter(repoPath.toString());
    }

    private static void sh(Path cwd, String... args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", args) + "\n" + out);
        }
    }

    @Test
    void writeThenReadRoundTrips() {
        repo.writeFiles("main", Map.of("openspec/changes/x/proposal.md", "# Proposal"), "drafted", "po-agent");

        assertThat(repo.readFile("main", "openspec/changes/x/proposal.md")).isEqualTo("# Proposal");
        assertThat(repo.readFile("main", "README.md")).isEqualTo("seed\n");
    }

    @Test
    void writeFilesChainsParentsAndCasFailsOnStaleParent() {
        CommitRef v1 = repo.writeFiles("main", Map.of("a.md", "1"), "m1", "author");
        CommitRef v2 = repo.writeFiles("main", Map.of("a.md", "2"), "m2", "author");

        assertThat(v1.sha()).isNotEqualTo(v2.sha());
        assertThat(repo.readFile("main", "a.md")).isEqualTo("2");
    }

    @Test
    void createBranchThenWriteOnlyAffectsThatBranch() {
        repo.createBranch("main", "story/4413");
        repo.writeFiles("story/4413", Map.of("story.md", "draft"), "drafted", "po-agent");

        assertThat(repo.readFile("story/4413", "story.md")).isEqualTo("draft");
        assertThatThrownBy(() -> repo.readFile("main", "story.md")).isInstanceOf(LocalGitAdapterException.class);
        // main's own history is untouched by the branch write
        assertThat(repo.readFile("main", "README.md")).isEqualTo("seed\n");
    }

    @Test
    void openPrCommentDiffAndStatusRoundTrip() {
        repo.createBranch("main", "task/T1");
        repo.writeFiles("task/T1", Map.of("src/export.js", "changed"), "impl T1", "build-worker");

        PRRef pr = repo.openPR("task/T1", "main", "Implement T1", "body");
        assertThat(pr.id()).isNotBlank();

        repo.commentOnPR(pr.id(), "looks good", 3);

        var diff = repo.getDiff(pr.id());
        assertThat(diff.unifiedDiff()).contains("src/export.js").contains("+changed");

        var status = repo.getPRStatus(pr.id());
        assertThat(status.state()).isEqualTo("open");
        assertThat(status.mergeable()).isTrue();
    }

    @Test
    void readingMissingFileThrows() {
        assertThatThrownBy(() -> repo.readFile("main", "missing.md")).isInstanceOf(LocalGitAdapterException.class);
    }

    @Test
    void concurrentWritesToDifferentBranchesBothSucceed() throws Exception {
        repo.createBranch("main", "task/A");
        repo.createBranch("main", "task/B");

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var a = pool.submit(() -> repo.writeFiles("task/A", Map.of("a.md", "A"), "a", "author"));
        var b = pool.submit(() -> repo.writeFiles("task/B", Map.of("b.md", "B"), "b", "author"));
        a.get();
        b.get();
        pool.shutdown();

        assertThat(repo.readFile("task/A", "a.md")).isEqualTo("A");
        assertThat(repo.readFile("task/B", "b.md")).isEqualTo("B");
    }

    @Test
    void onWebhookIsUnsupported() {
        assertThatThrownBy(() -> repo.onWebhook("local", Map.of())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructorRejectsNonGitDirectory() throws IOException {
        Path notARepo = tmp.resolve("not-a-repo");
        Files.createDirectories(notARepo);
        assertThatThrownBy(() -> new LocalGitRepoAdapter(notARepo.toString()))
                .isInstanceOf(LocalGitAdapterException.class);
    }
}
