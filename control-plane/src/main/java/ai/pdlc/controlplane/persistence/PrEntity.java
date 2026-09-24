package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row per (story, repo): the {@link ai.pdlc.core.port.RepoPort}-native PR id for the branch
 * opened against that repo — a multi-repo story opens one PR per repo it touched. */
@Table("prs")
public record PrEntity(
        @Id UUID id,
        UUID workItemId,
        String prId,
        String branch,
        String target,
        String repoId,
        OffsetDateTime createdAt) {

    public static PrEntity newRow(UUID workItemId, String prId, String branch, String target, String repoId) {
        return new PrEntity(null, workItemId, prId, branch, target, repoId, OffsetDateTime.now());
    }
}
