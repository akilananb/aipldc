package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.projects.ProjectService;
import ai.pdlc.controlplane.web.dto.ProjectDto;
import ai.pdlc.controlplane.web.dto.ProjectRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin CRUD for DB-backed projects. Read endpoints are open (the project name/gates are shown
 * across the UI to every role); {@code PUT}/{@code DELETE} require the {@code Admin} role, enforced
 * in {@link ProjectService} from the resolved {@link Identity}.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectsController {

    private final ProjectService service;
    private final IdentityResolver identityResolver;

    public ProjectsController(ProjectService service, IdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public List<ProjectDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ProjectDto get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public ProjectDto put(@PathVariable String id, @RequestBody ProjectRequest request, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        return service.upsert(id, request, identity);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        service.delete(id, identity);
        return ResponseEntity.noContent().build();
    }
}
