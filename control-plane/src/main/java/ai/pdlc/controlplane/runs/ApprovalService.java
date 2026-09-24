package ai.pdlc.controlplane.runs;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.Capability;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.ApprovalDto;
import ai.pdlc.controlplane.web.dto.EffectDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Human decisions on writes (docs/phase-2-execution-spec.md slice 2.2), in the runs module.
 *
 * <ul>
 *   <li>An approval is decided by a workspace {@code REVIEWER} who did not start the run
 *       (maker-checker), and only while it is {@code PENDING}. It authorizes exactly the tool
 *       version and arguments it was requested for; the executor re-checks that at call time.</li>
 *   <li>An {@code UNKNOWN} effect is resolved by an {@code OPERATOR}: {@code SUCCEEDED}/{@code FAILED}
 *       as found at the target, or {@code RETRY} when sending again is judged safe.</li>
 * </ul>
 * The decision is written first, then the run's workflow is signalled. A lost signal delays the
 * run until the approval's escalation time, where the workflow re-reads the decision; for effects
 * the operator can resolve again to re-signal.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED");
    static final Set<String> RESOLUTIONS = Set.of("SUCCEEDED", "FAILED", "RETRY");

    private final ApprovalStore store;
    private final RunLauncher launcher;
    private final WorkspaceService workspaces;

    public ApprovalService(ApprovalStore store, RunLauncher launcher, WorkspaceService workspaces) {
        this.store = store;
        this.launcher = launcher;
        this.workspaces = workspaces;
    }

    public List<ApprovalDto> list(String workspaceId, String status, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + STATUSES);
        }
        return store.list(workspaceId, status);
    }

    public ApprovalDto approve(String workspaceId, String approvalId, String reason, Identity identity) {
        return decide(workspaceId, approvalId, "APPROVED", reason, identity);
    }

    public ApprovalDto reject(String workspaceId, String approvalId, String reason, Identity identity) {
        return decide(workspaceId, approvalId, "REJECTED", reason, identity);
    }

    private ApprovalDto decide(String workspaceId, String approvalId, String status, String reason, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.REVIEWER);
        ApprovalDto approval = findApproval(workspaceId, approvalId);
        if (approval.runCreatedBy().equals(identity.user())) {
            throw new ForbiddenException("The user who started run " + approval.runId() + " cannot decide its writes");
        }
        if (!"PENDING".equals(approval.status())
                || !store.decide(UUID.fromString(approval.id()), status, identity.user(), blankToNull(reason))) {
            throw new ConflictException("Approval " + approvalId + " is no longer pending");
        }
        signal(approval.runId(), wf -> launcher.signalApproval(wf, approval.id()));
        return findApproval(workspaceId, approvalId);
    }

    public List<EffectDto> effects(String workspaceId, String runId, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return store.effects(runInWorkspace(workspaceId, runId));
    }

    public EffectDto resolve(String workspaceId, String runId, String effectId, String outcome, String note, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.OPERATOR);
        if (!RESOLUTIONS.contains(outcome)) {
            throw new IllegalArgumentException("outcome must be one of " + RESOLUTIONS);
        }
        UUID run = runInWorkspace(workspaceId, runId);
        EffectDto effect = store.effect(run, parse(effectId, "effect")).orElseThrow(() -> new NotFoundException("No effect " + effectId));
        if (!"UNKNOWN".equals(effect.state()) || !store.resolve(UUID.fromString(effect.id()), outcome, identity.user(), blankToNull(note))) {
            throw new ConflictException("Effect " + effectId + " is " + effect.state() + ", not UNKNOWN");
        }
        signal(runId, wf -> launcher.signalEffect(wf, effect.id()));
        return store.effect(run, UUID.fromString(effect.id())).orElseThrow();
    }

    private ApprovalDto findApproval(String workspaceId, String approvalId) {
        return store.find(workspaceId, parse(approvalId, "approval"))
                .orElseThrow(() -> new NotFoundException("No approval " + approvalId + " in " + workspaceId));
    }

    /** Runs are addressed through their workspace; another workspace's run is indistinguishable from none. */
    private UUID runInWorkspace(String workspaceId, String runId) {
        UUID id = parse(runId, "run");
        if (!store.runWorkspace(id).map(workspaceId::equals).orElse(false)) {
            throw new NotFoundException("No run " + runId + " in " + workspaceId);
        }
        return id;
    }

    private void signal(String runId, java.util.function.Consumer<String> send) {
        String workflowId = store.workflowId(UUID.fromString(runId)).orElse(null);
        if (workflowId == null) {
            return;
        }
        try {
            send.accept(workflowId);
        } catch (RuntimeException e) {
            log.warn("Recorded the decision, but signalling run {} failed ({}); it resumes at its next escalation check",
                    runId, e.toString());
        }
    }

    private static UUID parse(String id, String what) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("No " + what + " " + id);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
