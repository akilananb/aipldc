package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.runs.RunService;
import ai.pdlc.controlplane.web.dto.RunDto;
import ai.pdlc.controlplane.web.dto.StartRunRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Durable single-agent runs (docs/phase-1-execution-spec.md slice 4); rules live in {@link RunService}. */
@RestController
public class AgentRunsController {

    private final RunService service;
    private final IdentityResolver identityResolver;

    public AgentRunsController(RunService service, IdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/api/workspaces/{workspaceId}/agents/{agentId}/runs")
    public RunDto start(@PathVariable String workspaceId, @PathVariable String agentId,
                        @RequestBody StartRunRequest request, HttpServletRequest http) {
        return service.start(workspaceId, agentId, request, identityResolver.resolve(http));
    }

    @GetMapping("/api/workspaces/{workspaceId}/agents/{agentId}/runs")
    public List<RunDto> list(@PathVariable String workspaceId, @PathVariable String agentId, HttpServletRequest http) {
        return service.list(workspaceId, agentId, identityResolver.resolve(http));
    }

    @GetMapping("/api/workspaces/{workspaceId}/runs/{runId}")
    public RunDto get(@PathVariable String workspaceId, @PathVariable String runId, HttpServletRequest http) {
        return service.get(workspaceId, runId, identityResolver.resolve(http));
    }

    @PostMapping("/api/workspaces/{workspaceId}/runs/{runId}/cancel")
    public RunDto cancel(@PathVariable String workspaceId, @PathVariable String runId, HttpServletRequest http) {
        return service.cancel(workspaceId, runId, identityResolver.resolve(http));
    }
}
