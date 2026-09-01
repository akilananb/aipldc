package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row per story: the {@link ai.pdlc.core.port.RepoPort}-native PR id for its shared branch. */
@Table("prs")
public record PrEntity(
        @Id UUID id,
        UUID workItemId,
        String prId,
        String branch,
        String target,
        OffsetDateTime createdAt) {

    public static PrEntity newRow(UUID workItemId, String prId, String branch, String target) {
        return new PrEntity(null, workItemId, prId, branch, target, OffsetDateTime.now());
    }
}
