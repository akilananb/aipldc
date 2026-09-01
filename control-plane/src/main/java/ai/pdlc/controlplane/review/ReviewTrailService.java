package ai.pdlc.controlplane.review;

import ai.pdlc.controlplane.persistence.ReviewEventEntity;
import ai.pdlc.controlplane.persistence.ReviewEventRepository;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.review.ReviewMdWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Shared by {@link ai.pdlc.controlplane.temporal.BoardSideEffectsImpl} (workflow-triggered writes)
 * and the REST comment/approve endpoints (human-triggered writes): appends a {@code review.md}
 * block via {@link RepoPort} and inserts the matching {@code review_events} row - the two trails
 * that must agree (tech-stack §3.3).
 */
@Service
public class ReviewTrailService {

    private static final String REVIEW_MD_FILE = "review.md";

    private final PdlcConfig pdlcConfig;
    private final RepoPort repo;
    private final ReviewEventRepository reviewEvents;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReviewTrailService(PdlcConfig pdlcConfig, RepoPort repo, ReviewEventRepository reviewEvents) {
        this.pdlcConfig = pdlcConfig;
        this.repo = repo;
        this.reviewEvents = reviewEvents;
    }

    /** Retries on an optimistic-concurrency conflict: two writers (e.g. a human's REST approve
     * call and the workflow's own gate-passed transition) can race to update {@code review.md} on
     * the same branch. Each attempt re-reads the latest content, so the loser simply re-appends
     * its block on top of whatever the winner wrote - both trails (review.md in git, review_events
     * in Postgres, tech-stack §3.3) stay append-only and this never drops a block. */
    private static final int MAX_ATTEMPTS = 5;

    public void appendReviewMd(WorkItemRef story, String slug, String block) {
        String path = slug + "/" + REVIEW_MD_FILE;
        String defaultBranch = pdlcConfig.profile(story.profile()).repo().defaultBranch();
        RuntimeException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String existing;
            try {
                existing = repo.readFile(defaultBranch, path);
            } catch (RuntimeException notFound) {
                existing = ReviewMdWriter.header(slug);
            }
            String updated = ReviewMdWriter.append(existing, block);
            try {
                repo.writeFiles(defaultBranch, Map.of(path, updated), "review.md updated", "control-plane");
                return;
            } catch (RuntimeException conflict) {
                lastConflict = conflict;
            }
        }
        throw lastConflict;
    }

    public String readReviewMd(WorkItemRef story, String slug) {
        String defaultBranch = pdlcConfig.profile(story.profile()).repo().defaultBranch();
        return repo.readFile(defaultBranch, slug + "/" + REVIEW_MD_FILE);
    }

    public void appendReviewEvent(UUID workItemId, String kind, Map<String, Object> payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        reviewEvents.save(ReviewEventEntity.newRow(workItemId, kind, json));
    }
}
