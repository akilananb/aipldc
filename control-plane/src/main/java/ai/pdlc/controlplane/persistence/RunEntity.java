package ai.pdlc.controlplane.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("runs")
public record RunEntity(
        @Id UUID id,
        UUID workItemId,
        String agent,
        String phase,
        String workflowRunId,
        String traceUrl,
        Long tokens,
        Integer iterations,
        String outcome,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {
}
