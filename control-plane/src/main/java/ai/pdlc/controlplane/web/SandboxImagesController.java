package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.sandbox.SandboxImageService;
import ai.pdlc.controlplane.web.dto.SandboxImageDto;
import ai.pdlc.controlplane.web.dto.SandboxImageRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The enterprise sandbox image catalog (docs/phase-2-execution-spec.md slice 2.4). Rules live in {@link SandboxImageService}. */
@RestController
@RequestMapping("/api/platform/sandbox-images")
public class SandboxImagesController {

    private final SandboxImageService service;
    private final IdentityResolver identityResolver;

    public SandboxImagesController(SandboxImageService service, IdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    /** Any signed-in user: the tool editor offers the catalog. */
    @GetMapping
    public List<SandboxImageDto> list(HttpServletRequest http) {
        identityResolver.resolve(http);
        return service.list();
    }

    @GetMapping("/{id}")
    public SandboxImageDto get(@PathVariable String id, HttpServletRequest http) {
        identityResolver.resolve(http);
        return service.get(id);
    }

    @PostMapping
    public SandboxImageDto create(@RequestBody SandboxImageRequest request, HttpServletRequest http) {
        return service.create(request, identityResolver.resolve(http));
    }

    @PutMapping("/{id}")
    public SandboxImageDto update(@PathVariable String id, @RequestBody SandboxImageRequest request, HttpServletRequest http) {
        return service.update(id, request, identityResolver.resolve(http));
    }

    @PostMapping("/{id}/retire")
    public SandboxImageDto retire(@PathVariable String id, HttpServletRequest http) {
        return service.retire(id, identityResolver.resolve(http));
    }
}
