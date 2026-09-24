package ai.pdlc.controlplane.runs;

import ai.pdlc.controlplane.web.dto.ApprovalDto;
import ai.pdlc.controlplane.web.dto.EffectDto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The control-plane side of approvals and effects (docs/phase-2-execution-spec.md slice 2.2): the
 * agents worker creates the rows; control-plane only records human decisions, each guarded on the
 * row still being open. {@link JdbcApprovalStore} in production.
 */
public interface ApprovalStore {

    /** {@code status} null = every status; newest first. */
    List<ApprovalDto> list(String workspaceId, String status);

    Optional<ApprovalDto> find(String workspaceId, UUID id);

    Optional<String> runWorkspace(UUID runId);

    /** The workflow to signal for this run. */
    Optional<String> workflowId(UUID runId);

    /** PENDING → APPROVED/REJECTED; false if it was no longer pending. */
    boolean decide(UUID id, String status, String decidedBy, String reason);

    List<EffectDto> effects(UUID runId);

    Optional<EffectDto> effect(UUID runId, UUID effectId);

    /**
     * UNKNOWN → SUCCEEDED/FAILED (as the operator found it) or INTENDED (RETRY: send again);
     * false if the effect was no longer UNKNOWN.
     */
    boolean resolve(UUID effectId, String resolution, String resolvedBy, String note);
}
