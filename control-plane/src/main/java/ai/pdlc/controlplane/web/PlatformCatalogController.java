package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.connections.ConnectionService;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.web.dto.ConnectionDto;
import ai.pdlc.controlplane.web.dto.ConnectionRequest;
import ai.pdlc.controlplane.web.dto.ModelDto;
import ai.pdlc.controlplane.web.dto.ModelRequest;
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
 * Enterprise model-provider connections and the model catalog (docs/phase-1-execution-spec.md
 * slice 3). Rules live in {@link ConnectionService}. Model ids may contain {@code /} (e.g.
 * {@code anthropic/claude-sonnet}), so the update path captures the rest of the URL.
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformCatalogController {

    private final ConnectionService service;
    private final IdentityResolver identityResolver;

    public PlatformCatalogController(ConnectionService service, IdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/connections")
    public List<ConnectionDto> connections(HttpServletRequest http) {
        return service.connections(identityResolver.resolve(http));
    }

    @PostMapping("/connections")
    public ConnectionDto createConnection(@RequestBody ConnectionRequest request, HttpServletRequest http) {
        return service.createConnection(request, identityResolver.resolve(http));
    }

    @PutMapping("/connections/{id}")
    public ConnectionDto updateConnection(@PathVariable String id, @RequestBody ConnectionRequest request,
                                          HttpServletRequest http) {
        return service.updateConnection(id, request, identityResolver.resolve(http));
    }

    @PostMapping("/connections/{id}/revoke")
    public ConnectionDto revokeConnection(@PathVariable String id, HttpServletRequest http) {
        return service.revokeConnection(id, identityResolver.resolve(http));
    }

    /** Any signed-in user: the agent editor lists bindable models. */
    @GetMapping("/models")
    public List<ModelDto> models(HttpServletRequest http) {
        identityResolver.resolve(http);
        return service.models();
    }

    @PostMapping("/models")
    public ModelDto createModel(@RequestBody ModelRequest request, HttpServletRequest http) {
        return service.createModel(request, identityResolver.resolve(http));
    }

    @PutMapping("/models/{*id}")
    public ModelDto updateModel(@PathVariable String id, @RequestBody ModelRequest request, HttpServletRequest http) {
        return service.updateModel(id.startsWith("/") ? id.substring(1) : id, request, identityResolver.resolve(http));
    }
}
