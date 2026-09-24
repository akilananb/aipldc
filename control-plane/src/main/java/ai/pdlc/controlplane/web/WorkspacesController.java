package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.platform.WorkspaceStore;
import ai.pdlc.controlplane.web.dto.WorkspaceDto;
import ai.pdlc.controlplane.web.dto.WorkspaceMemberDto;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
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
 * Platform workspaces (configurable-agent-platform.md §3). Unlike {@link ProjectsController}, reads
 * are not open: every endpoint resolves the caller, and a non-member gets 404. Authorization lives
 * in {@link WorkspaceService}.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspacesController {

    private final WorkspaceService service;
    private final IdentityResolver identityResolver;

    public WorkspacesController(WorkspaceService service, IdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public List<WorkspaceDto> list(HttpServletRequest http) {
        return service.list(identityResolver.resolve(http));
    }

    @PostMapping
    public WorkspaceDto create(@RequestBody WorkspaceRequest request, HttpServletRequest http) {
        return service.create(request, identityResolver.resolve(http));
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceDto get(@PathVariable String workspaceId, HttpServletRequest http) {
        return service.get(workspaceId, identityResolver.resolve(http));
    }

    @GetMapping("/{workspaceId}/members")
    public List<WorkspaceMemberDto> members(@PathVariable String workspaceId, HttpServletRequest http) {
        return service.members(workspaceId, identityResolver.resolve(http));
    }

    @GetMapping("/{workspaceId}/projects")
    public List<WorkspaceStore.LinkedProject> projects(@PathVariable String workspaceId, HttpServletRequest http) {
        return service.projects(workspaceId, identityResolver.resolve(http));
    }

    /** Replaces the member's capabilities; an empty set removes them from the workspace. */
    @PutMapping("/{workspaceId}/members/{userId}")
    public WorkspaceMemberDto setMember(@PathVariable String workspaceId, @PathVariable String userId,
                                        @RequestBody WorkspaceMemberDto request, HttpServletRequest http) {
        return service.setMember(workspaceId, userId, request.capabilities(), identityResolver.resolve(http));
    }
}
