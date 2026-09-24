package ai.pdlc.controlplane.runs;

import ai.pdlc.controlplane.web.dto.ApprovalDto;
import ai.pdlc.controlplane.web.dto.EffectDto;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-process {@link ApprovalStore} for {@link ApprovalServiceTest}; the agents worker's inserts are simulated by {@link #add}. */
class InMemoryApprovalStore implements ApprovalStore {

    record Run(String workspaceId, String workflowId) {
    }

    final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    final Map<String, ApprovalDto> approvals = new ConcurrentHashMap<>();
    final Map<String, EffectDto> effects = new ConcurrentHashMap<>();

    ApprovalDto add(String workspaceId, UUID runId, String runCreatedBy) {
        runs.put(runId, new Run(workspaceId, "agent-run-" + runId));
        ApprovalDto a = new ApprovalDto(UUID.randomUUID().toString(), workspaceId, runId.toString(), "order-bot", 1, runCreatedBy,
                1, "call-1", "cancel-order", 1, "POST", "/orders/{orderId}/cancel", "orders-api", "{\"orderId\":\"42\"}",
                "sha256:abc", "PENDING", OffsetDateTime.now(), null, null, null, null);
        approvals.put(a.id(), a);
        return a;
    }

    EffectDto addEffect(UUID runId, String state) {
        EffectDto e = new EffectDto(UUID.randomUUID().toString(), runId.toString(), UUID.randomUUID().toString(), "cancel-order", 1,
                runId + ":1:call-1", state, 1, null, null, null, null, null, OffsetDateTime.now(), OffsetDateTime.now());
        effects.put(e.id(), e);
        return e;
    }

    @Override
    public List<ApprovalDto> list(String workspaceId, String status) {
        return approvals.values().stream().filter(a -> a.workspaceId().equals(workspaceId))
                .filter(a -> status == null || a.status().equals(status)).toList();
    }

    @Override
    public Optional<ApprovalDto> find(String workspaceId, UUID id) {
        return Optional.ofNullable(approvals.get(id.toString())).filter(a -> a.workspaceId().equals(workspaceId));
    }

    @Override
    public Optional<String> runWorkspace(UUID runId) {
        return Optional.ofNullable(runs.get(runId)).map(Run::workspaceId);
    }

    @Override
    public Optional<String> workflowId(UUID runId) {
        return Optional.ofNullable(runs.get(runId)).map(Run::workflowId);
    }

    @Override
    public boolean decide(UUID id, String status, String decidedBy, String reason) {
        ApprovalDto a = approvals.get(id.toString());
        if (a == null || !a.status().equals("PENDING")) {
            return false;
        }
        approvals.put(a.id(), new ApprovalDto(a.id(), a.workspaceId(), a.runId(), a.agentId(), a.agentVersion(), a.runCreatedBy(),
                a.turn(), a.callId(), a.toolId(), a.toolVersion(), a.method(), a.path(), a.connectionId(), a.argsJson(),
                a.argsHash(), status, a.requestedAt(), a.escalatedAt(), decidedBy, OffsetDateTime.now(), reason));
        return true;
    }

    @Override
    public List<EffectDto> effects(UUID runId) {
        return new ArrayList<>(effects.values().stream().filter(e -> e.runId().equals(runId.toString())).toList());
    }

    @Override
    public Optional<EffectDto> effect(UUID runId, UUID effectId) {
        return Optional.ofNullable(effects.get(effectId.toString())).filter(e -> e.runId().equals(runId.toString()));
    }

    @Override
    public boolean resolve(UUID effectId, String resolution, String resolvedBy, String note) {
        EffectDto e = effects.get(effectId.toString());
        if (e == null || !e.state().equals("UNKNOWN")) {
            return false;
        }
        effects.put(e.id(), new EffectDto(e.id(), e.runId(), e.approvalId(), e.toolId(), e.toolVersion(), e.idempotencyKey(),
                "RETRY".equals(resolution) ? "INTENDED" : resolution, e.sendCount(), e.httpStatus(), resolution, resolvedBy,
                OffsetDateTime.now(), note, e.createdAt(), OffsetDateTime.now()));
        return true;
    }
}
