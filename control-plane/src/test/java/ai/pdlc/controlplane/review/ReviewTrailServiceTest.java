package ai.pdlc.controlplane.review;

import ai.pdlc.controlplane.config.PortRegistry;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.ProjectMeta;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CommitRef;
import ai.pdlc.core.domain.Diff;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.PRStatus;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.RepoPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link ReviewTrailService#appendReviewMd} must retry a concurrent-writer conflict (two threads
 * racing to update {@code review.md} on the same branch - e.g. a human's REST approve call and the
 * workflow's own gate-passed transition), not lose the block or fail the caller's request. */
class ReviewTrailServiceTest {

    private static final Profile PROFILE = new Profile(
            "local",
            new ProjectMeta("local", "local", null, List.of(), "", null),
            new BoardConfig("in-memory", "local", "PDLC", Map.of(), Map.of(), new BoardConfig.AuthConfig("none", "kv://none")),
            List.of(new RepoConfig("main", "local-git", "/tmp/x", "main", "openspec", List.of(), true)),
            new NotifyConfig("none", "none"),
            new AgentsConfig("http://x", null, Map.of()),
            Map.of());

    private static final WorkItemRef STORY = new WorkItemRef("local", "4413");

    private static ReviewTrailService newService(RepoPort repo) {
        ProjectDirectory projects = mock(ProjectDirectory.class);
        when(projects.project("local")).thenReturn(PROFILE);
        PortRegistry ports = mock(PortRegistry.class);
        when(ports.primaryRepo("local")).thenReturn(repo);
        return new ReviewTrailService(projects, ports, null);
    }

    /** In-memory fake standing in for LocalGitRepoAdapter's optimistic-concurrency CAS: the Nth
     * write (1-indexed) throws a conflict; every other write reads/writes a single in-memory file. */
    private static class ConflictingRepo implements RepoPort {
        String content;
        final AtomicInteger writeCount = new AtomicInteger();
        int failOnAttempt = -1; // -1 = never fail

        @Override
        public String readFile(String ref, String path) {
            if (content == null) {
                throw new IllegalStateException("not found");
            }
            return content;
        }

        @Override
        public CommitRef writeFiles(String branch, Map<String, String> files, String message, String authorName) {
            int attempt = writeCount.incrementAndGet();
            if (attempt == failOnAttempt) {
                throw new RuntimeException("git update-ref refs/heads/main failed (128): cannot lock ref");
            }
            content = files.values().iterator().next();
            return new CommitRef("sha-" + attempt);
        }

        @Override
        public void createBranch(String from, String name) {
        }

        @Override
        public String resolveRef(String ref) {
            return "sha-" + writeCount.get();
        }

        @Override
        public PRRef openPR(String branch, String target, String title, String body) {
            return null;
        }

        @Override
        public void commentOnPR(String prId, String body, Integer line) {
        }

        @Override
        public Diff getDiff(String prId) {
            return null;
        }

        @Override
        public PRStatus getPRStatus(String prId) {
            return null;
        }

        @Override
        public CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent) {
            return null;
        }
    }

    @Test
    void retriesOnceOnAConflictAndStillWritesTheBlock() {
        ConflictingRepo repo = new ConflictingRepo();
        repo.failOnAttempt = 1; // first attempt loses the race, second (after re-read) succeeds
        ReviewTrailService service = newService(repo);

        service.appendReviewMd(STORY, "openspec/changes/x", "\nblock-a\n");

        assertThat(repo.content).contains("block-a");
        assertThat(repo.writeCount.get()).isEqualTo(2);
    }

    @Test
    void aLoserReReadsSoBothBlocksSurviveTheRace() {
        ConflictingRepo repo = new ConflictingRepo();
        ReviewTrailService service = newService(repo);

        // Simulates the winner writing first (as if from a concurrent thread), then the loser's
        // retry re-reading that content before appending its own block.
        service.appendReviewMd(STORY, "openspec/changes/x", "\nblock-winner\n");
        repo.failOnAttempt = repo.writeCount.get() + 1; // the loser's first attempt conflicts
        service.appendReviewMd(STORY, "openspec/changes/x", "\nblock-loser\n");

        assertThat(repo.content).contains("block-winner").contains("block-loser");
    }

    @Test
    void exhaustingRetriesPropagatesTheConflict() {
        ConflictingRepo repo = new ConflictingRepo() {
            @Override
            public CommitRef writeFiles(String branch, Map<String, String> files, String message, String authorName) {
                writeCount.incrementAndGet();
                throw new RuntimeException("git update-ref refs/heads/main failed (128): cannot lock ref");
            }
        };
        ReviewTrailService service = newService(repo);

        assertThatThrownBy(() -> service.appendReviewMd(STORY, "openspec/changes/x", "\nblock-a\n"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cannot lock ref");
        assertThat(repo.writeCount.get()).isEqualTo(5); // MAX_ATTEMPTS, then gives up
    }
}
