package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.temporal.BuildTaskService;
import ai.pdlc.controlplane.web.dto.ClaimRequest;
import ai.pdlc.controlplane.web.dto.FailRequest;
import ai.pdlc.core.workflow.BuildResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The standalone build agent's only boundary to control-plane — {@code POST /claim} for a task,
 * {@code POST /{id}/heartbeat} while working it, {@code POST /{id}/result} or {@code
 * POST /{id}/fail} to finish. No Temporal client on the agent side; every call here is a thin
 * wrapper over {@link BuildTaskService}, which owns the {@code build_tasks} rows and resolves the
 * parked Temporal activity. Auth: a single shared secret ({@code X-Agent-Token}), optional -
 * matches the pilot's {@code X-User} header-trust model.
 */
@RestController
@RequestMapping("/api/build-tasks")
public class BuildTasksController {
    private final BuildTaskService service;
    private final String agentToken;

    // Own Jackson 2 mapper rather than an injected Spring bean: Spring Boot 4's Jackson
    // auto-configuration wires a `tools.jackson` (Jackson 3) ObjectMapper for HTTP message
    // conversion, not `com.fasterxml.jackson.databind.ObjectMapper` - and the stored
    // `payload_json` (BuildTaskService#enqueue) is itself Jackson 2 output.
    private final ObjectMapper mapper = new ObjectMapper();

    public BuildTasksController(BuildTaskService service, @Value("${pdlc.build-agent.token:}") String agentToken) {
        this.service = service;
        this.agentToken = agentToken;
    }

    @PostMapping("/claim")
    public ResponseEntity<Map<String, Object>> claim(@RequestBody ClaimRequest request, HttpServletRequest httpRequest) {
        checkAuth(httpRequest);
        if (request.agent() == null || request.agent().isBlank()) {
            throw new IllegalArgumentException("agent is required");
        }
        if (request.filters() == null || request.filters().profile() == null || request.filters().profile().isBlank()) {
            throw new IllegalArgumentException("filters.profile is required");
        }

        Optional<BuildTaskService.ClaimedRow> claimed = service.claim(
                request.agent(), request.filters().profile(), request.filters().story(), request.filters().task());
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        BuildTaskService.ClaimedRow row = claimed.get();
        return ResponseEntity.ok(Map.of(
                "id", row.id().toString(),
                "attempt", row.attempt(),
                "payload", readPayload(row.payloadJson())));
    }

    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable UUID id, HttpServletRequest httpRequest) {
        checkAuth(httpRequest);
        service.heartbeat(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/result")
    public ResponseEntity<Void> result(@PathVariable UUID id, @RequestBody BuildResult result, HttpServletRequest httpRequest) {
        checkAuth(httpRequest);
        service.complete(id, result);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<Void> fail(@PathVariable UUID id, @RequestBody FailRequest request, HttpServletRequest httpRequest) {
        checkAuth(httpRequest);
        service.fail(id, request.message());
        return ResponseEntity.ok().build();
    }

    private void checkAuth(HttpServletRequest httpRequest) {
        if (!agentToken.isEmpty() && !agentToken.equals(httpRequest.getHeader("X-Agent-Token"))) {
            throw new ForbiddenException("invalid or missing X-Agent-Token");
        }
    }

    private Map<String, Object> readPayload(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to parse stored build task payload", e);
        }
    }
}
