package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.runs.ApprovalService;
import ai.pdlc.controlplane.web.dto.ApprovalDecisionRequest;
import ai.pdlc.controlplane.web.dto.ApprovalDto;
import ai.pdlc.controlplane.web.dto.EffectDto;
import ai.pdlc.controlplane.web.dto.ResolveEffectRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The write-approval inbox and effect resolution (docs/phase-2-execution-spec.md slice 2.2); rules live in {@link ApprovalService}. */
@RestController
public class ApprovalsController {

    private final ApprovalService service;
    private final IdentityResolver identityResolver;

    public ApprovalsController(ApprovalService service, IdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/api/workspaces/{workspaceId}/approvals")
    public List<ApprovalDto> list(@PathVariable String workspaceId, @RequestParam(required = false) String status,
                                  HttpServletRequest http) {
        return service.list(workspaceId, status, identityResolver.resolve(http));
    }

    @PostMapping("/api/workspaces/{workspaceId}/approvals/{approvalId}/approve")
    public ApprovalDto approve(@PathVariable String workspaceId, @PathVariable String approvalId,
                               @RequestBody(required = false) ApprovalDecisionRequest request, HttpServletRequest http) {
        return service.approve(workspaceId, approvalId, request == null ? null : request.reason(), identityResolver.resolve(http));
    }

    @PostMapping("/api/workspaces/{workspaceId}/approvals/{approvalId}/reject")
    public ApprovalDto reject(@PathVariable String workspaceId, @PathVariable String approvalId,
                              @RequestBody(required = false) ApprovalDecisionRequest request, HttpServletRequest http) {
        return service.reject(workspaceId, approvalId, request == null ? null : request.reason(), identityResolver.resolve(http));
    }

    @GetMapping("/api/workspaces/{workspaceId}/runs/{runId}/effects")
    public List<EffectDto> effects(@PathVariable String workspaceId, @PathVariable String runId, HttpServletRequest http) {
        return service.effects(workspaceId, runId, identityResolver.resolve(http));
    }

    @PostMapping("/api/workspaces/{workspaceId}/runs/{runId}/effects/{effectId}/resolve")
    public EffectDto resolve(@PathVariable String workspaceId, @PathVariable String runId, @PathVariable String effectId,
                             @RequestBody ResolveEffectRequest request, HttpServletRequest http) {
        return service.resolve(workspaceId, runId, effectId, request.outcome(), request.note(), identityResolver.resolve(http));
    }
}
