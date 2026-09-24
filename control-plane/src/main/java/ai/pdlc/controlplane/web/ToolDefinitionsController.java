package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.platform.ToolRegistryService;
import ai.pdlc.controlplane.web.dto.AgentLifecycleRequest;
import ai.pdlc.controlplane.web.dto.ToolDefinitionDto;
import ai.pdlc.controlplane.web.dto.ToolDraftRequest;
import ai.pdlc.controlplane.web.dto.ToolValidationDto;
import ai.pdlc.controlplane.web.dto.ToolVersionDto;
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
 * Workspace-scoped ToolDefinition registry (docs/phase-2-execution-spec.md slice 2.1): draft
 * editing, validation, publication, rollback and retirement. Rules live in {@link ToolRegistryService}.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/tools")
public class ToolDefinitionsController {

    private final ToolRegistryService service;
    private final IdentityResolver identityResolver;

    public ToolDefinitionsController(ToolRegistryService service, IdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public List<ToolDefinitionDto> list(@PathVariable String workspaceId, HttpServletRequest http) {
        return service.list(workspaceId, identityResolver.resolve(http));
    }

    @PostMapping
    public ToolDefinitionDto create(@PathVariable String workspaceId, @RequestBody ToolDraftRequest request,
                                     HttpServletRequest http) {
        return service.create(workspaceId, request, identityResolver.resolve(http));
    }

    @GetMapping("/{toolId}")
    public ToolDefinitionDto get(@PathVariable String workspaceId, @PathVariable String toolId, HttpServletRequest http) {
        return service.get(workspaceId, toolId, identityResolver.resolve(http));
    }

    @PutMapping("/{toolId}/draft")
    public ToolDefinitionDto saveDraft(@PathVariable String workspaceId, @PathVariable String toolId,
                                        @RequestBody ToolDraftRequest request, HttpServletRequest http) {
        return service.saveDraft(workspaceId, toolId, request, identityResolver.resolve(http));
    }

    @PostMapping("/{toolId}/validate")
    public ToolValidationDto validate(@PathVariable String workspaceId, @PathVariable String toolId,
                                       HttpServletRequest http) {
        return service.validate(workspaceId, toolId, identityResolver.resolve(http));
    }

    @PostMapping("/{toolId}/publish")
    public ToolVersionDto publish(@PathVariable String workspaceId, @PathVariable String toolId,
                                   @RequestBody AgentLifecycleRequest request, HttpServletRequest http) {
        return service.publish(workspaceId, toolId, request.revision(), identityResolver.resolve(http));
    }

    @PostMapping("/{toolId}/rollback")
    public ToolDefinitionDto rollback(@PathVariable String workspaceId, @PathVariable String toolId,
                                       @RequestBody AgentLifecycleRequest request, HttpServletRequest http) {
        return service.rollback(workspaceId, toolId, request.version(), identityResolver.resolve(http));
    }

    @PostMapping("/{toolId}/retire")
    public ToolDefinitionDto retire(@PathVariable String workspaceId, @PathVariable String toolId,
                                     HttpServletRequest http) {
        return service.retire(workspaceId, toolId, identityResolver.resolve(http));
    }

    @GetMapping("/{toolId}/versions")
    public List<ToolVersionDto> versions(@PathVariable String workspaceId, @PathVariable String toolId,
                                          HttpServletRequest http) {
        return service.versions(workspaceId, toolId, identityResolver.resolve(http));
    }

    @GetMapping("/{toolId}/versions/{version}")
    public ToolVersionDto version(@PathVariable String workspaceId, @PathVariable String toolId,
                                   @PathVariable int version, HttpServletRequest http) {
        return service.version(workspaceId, toolId, version, identityResolver.resolve(http));
    }
}
