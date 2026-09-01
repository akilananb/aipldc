package ai.pdlc.controlplane.review;

import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CommitRef;
import ai.pdlc.core.domain.Diff;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.PRStatus;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.RepoPort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ReviewTrailService#appendReviewMd} must retry a concurrent-writer conflict (two threads
 * racing to update {@code review.md} on the same branch - e.g. a human's REST approve call and the
 * workflow's own gate-passed transition), not lose the block or fail the caller's request. */
class ReviewTrailServiceTest {

    private static final PdlcConfig CONFIG = PdlcConfig.load(new ByteArrayInputStream("""
            profiles:
              local:
                board: { provider: in-memory, org: local, project: PDLC, types: {}, states: {}, auth: { kind: none, secret_ref: kv://none } }
                repo: { provider: local-git, url: /tmp/x, default_branch: main, spec_dir: openspec }
                notify: { provider: none, channel: none }
                agents: { gateway: http://x, roles: {} }
                gates: { G1: { roles: [], sod: false } }
            """.getBytes(StandardCharsets.UTF_8)));

    private static final WorkItemRef STORY = new WorkItemRef("local", "4413");

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
        ReviewTrailService service = new ReviewTrailService(CONFIG, repo, null);

        service.appendReviewMd(STORY, "openspec/changes/x", "\nblock-a\n");

        assertThat(repo.content).contains("block-a");
        assertThat(repo.writeCount.get()).isEqualTo(2);
    }

    @Test
    void aLoserReReadsSoBothBlocksSurviveTheRace() {
        ConflictingRepo repo = new ConflictingRepo();
        ReviewTrailService service = new ReviewTrailService(CONFIG, repo, null);

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
        ReviewTrailService service = new ReviewTrailService(CONFIG, repo, null);

        assertThatThrownBy(() -> service.appendReviewMd(STORY, "openspec/changes/x", "\nblock-a\n"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cannot lock ref");
        assertThat(repo.writeCount.get()).isEqualTo(5); // MAX_ATTEMPTS, then gives up
    }
}
