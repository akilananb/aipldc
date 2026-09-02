package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Task-detail-view payload (context lives on {@code ItemDetailDto.description}): the story's
 * proposal/design-spec/tasks-plan documents plus who approved them at gate 1 — sourced from the
 * durable {@code approvals} table, not the workflow-query {@code gate.approvals} (which resets
 * per gate episode and is empty once the story moves past G1). */
public record SpecDocsDto(
        UUID storyId,
        String storyTitle,
        String slug,
        String proposalMd,
        String specMd,
        String tasksMd,
        List<DocApproval> approvals) {

    public record DocApproval(String who, String role, int version, OffsetDateTime at) {
    }
}
