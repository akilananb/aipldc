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
        String workflowRunId,
        String traceUrl,
        Long tokens,
        Integer iterations,
        String outcome,
        OffsetDateTime createdAt) {

    public static RunEntity newRow(UUID workItemId, String agent, String workflowRunId, String traceUrl, Long tokens, Integer iterations, String outcome) {
        return new RunEntity(null, workItemId, agent, workflowRunId, traceUrl, tokens, iterations, outcome, OffsetDateTime.now());
    }
}
