package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.platform.AgentRegistryService;
import ai.pdlc.controlplane.web.dto.AgentDefinitionDto;
import ai.pdlc.controlplane.web.dto.AgentDraftRequest;
import ai.pdlc.controlplane.web.dto.AgentLifecycleRequest;
import ai.pdlc.controlplane.web.dto.AgentValidationDto;
import ai.pdlc.controlplane.web.dto.AgentVersionDto;
import ai.pdlc.controlplane.web.dto.ResolvedAgentDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Workspace-scoped AgentDefinition registry: draft editing, validation, publication, rollback,
 * retirement and run-time resolution. Lifecycle and authorization rules live in
 * {@link AgentRegistryService}.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/agents")
public class AgentDefinitionsController {

    private final AgentRegistryService service;
    private final IdentityResolver identityResolver;

    public AgentDefinitionsController(AgentRegistryService service, IdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public List<AgentDefinitionDto> list(@PathVariable String workspaceId, HttpServletRequest http) {
        return service.list(workspaceId, identityResolver.resolve(http));
    }

    @PostMapping
    public AgentDefinitionDto create(@PathVariable String workspaceId, @RequestBody AgentDraftRequest request,
                                     HttpServletRequest http) {
        return service.create(workspaceId, request, identityResolver.resolve(http));
    }

    @GetMapping("/{agentId}")
    public AgentDefinitionDto get(@PathVariable String workspaceId, @PathVariable String agentId, HttpServletRequest http) {
        return service.get(workspaceId, agentId, identityResolver.resolve(http));
    }

    @PutMapping("/{agentId}/draft")
    public AgentDefinitionDto saveDraft(@PathVariable String workspaceId, @PathVariable String agentId,
                                        @RequestBody AgentDraftRequest request, HttpServletRequest http) {
        return service.saveDraft(workspaceId, agentId, request, identityResolver.resolve(http));
    }

    @PostMapping("/{agentId}/validate")
    public AgentValidationDto validate(@PathVariable String workspaceId, @PathVariable String agentId,
                                       HttpServletRequest http) {
        return service.validate(workspaceId, agentId, identityResolver.resolve(http));
    }

    @PostMapping("/{agentId}/publish")
    public AgentVersionDto publish(@PathVariable String workspaceId, @PathVariable String agentId,
                                   @RequestBody AgentLifecycleRequest request, HttpServletRequest http) {
        return service.publish(workspaceId, agentId, request.revision(), identityResolver.resolve(http));
    }

    @PostMapping("/{agentId}/rollback")
    public AgentDefinitionDto rollback(@PathVariable String workspaceId, @PathVariable String agentId,
                                       @RequestBody AgentLifecycleRequest request, HttpServletRequest http) {
        return service.rollback(workspaceId, agentId, request.version(), identityResolver.resolve(http));
    }

    @PostMapping("/{agentId}/retire")
    public AgentDefinitionDto retire(@PathVariable String workspaceId, @PathVariable String agentId,
                                     HttpServletRequest http) {
        return service.retire(workspaceId, agentId, identityResolver.resolve(http));
    }

    @GetMapping("/{agentId}/versions")
    public List<AgentVersionDto> versions(@PathVariable String workspaceId, @PathVariable String agentId,
                                          HttpServletRequest http) {
        return service.versions(workspaceId, agentId, identityResolver.resolve(http));
    }

    @GetMapping("/{agentId}/versions/{version}")
    public AgentVersionDto version(@PathVariable String workspaceId, @PathVariable String agentId,
                                   @PathVariable int version, HttpServletRequest http) {
        return service.version(workspaceId, agentId, version, identityResolver.resolve(http));
    }

    /** The pinned definition and model a new run would use right now (OPERATOR only). */
    @GetMapping("/{agentId}/resolved")
    public ResolvedAgentDto resolved(@PathVariable String workspaceId, @PathVariable String agentId,
                                    HttpServletRequest http) {
        return service.resolveForRun(workspaceId, agentId, identityResolver.resolve(http));
    }
}
