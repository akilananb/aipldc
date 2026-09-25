package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.temporal.AgentPresenceService;
import ai.pdlc.controlplane.temporal.BuildTaskService;
import ai.pdlc.controlplane.web.dto.ClaimRequest;
import ai.pdlc.controlplane.web.dto.FailRequest;
import ai.pdlc.controlplane.web.dto.PlanConsultationResultRequest;
import ai.pdlc.core.workflow.BuildResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The standalone build agent's only boundary to control-plane — {@code POST /claim} for a task,
 * {@code POST /{id}/heartbeat} while working it, {@code POST /{id}/result} or {@code
 * POST /{id}/plan-result} or {@code POST /{id}/fail} to finish. No Temporal client on the agent
 * side; every call here is a thin wrapper over {@link BuildTaskService}, which owns the {@code
 * build_tasks} rows and resolves the parked Temporal activity. {@code /claim} returns a
 * per-claim {@code leaseToken} that every subsequent per-task call MUST present as the {@code
 * X-Lease-Token} header — a stale or mismatched token gets 410 (another worker has since
 * reclaimed the row) and a missing one gets 400. Auth is enforced by {@code
 * identity.SecurityConfig}, not here: a client-credentials JWT with scope {@code pdlc.build}, or
 * the shared {@code X-Agent-Token} when {@code BUILD_AGENT_TOKEN} is set (open only in dev-headers
 * mode with no token configured). Concurrent
 * workers in a pool MUST each use a distinct {@code agent} name (presence rows are keyed by
 * name); task fencing itself does not depend on it.
 */
@RestController
@RequestMapping("/api/build-tasks")
public class BuildTasksController {
    private final BuildTaskService service;
    private final AgentPresenceService presence;
    private final ai.pdlc.core.config.ProjectDirectory projects;

    // Own Jackson 2 mapper rather than an injected Spring bean: Spring Boot 4's Jackson
    // auto-configuration wires a `tools.jackson` (Jackson 3) ObjectMapper for HTTP message
    // conversion, not `com.fasterxml.jackson.databind.ObjectMapper` - and the stored
    // `payload_json` (BuildTaskService#enqueue) is itself Jackson 2 output.
    private final ObjectMapper mapper = new ObjectMapper();

    public BuildTasksController(BuildTaskService service, AgentPresenceService presence, ai.pdlc.core.config.ProjectDirectory projects) {
        this.service = service;
        this.presence = presence;
        this.projects = projects;
    }

    @PostMapping("/claim")
    public ResponseEntity<Map<String, Object>> claim(@RequestBody ClaimRequest request) {
        if (request.agent() == null || request.agent().isBlank()) {
            throw new IllegalArgumentException("agent is required");
        }
        if (request.filters() == null || request.filters().profile() == null || request.filters().profile().isBlank()) {
            throw new IllegalArgumentException("filters.profile is required");
        }
        if (projects.find(request.filters().profile()).isEmpty()) {
            throw new IllegalArgumentException("unknown project id '" + request.filters().profile()
                    + "' - must be one of " + projects.projects().keySet());
        }
        presence.touchAcpOnClaim(request.filters().profile(), request);

        Optional<BuildTaskService.ClaimedRow> claimed = service.claim(
                request.agent(), request.filters().profile(), request.filters().story(), request.filters().task());
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        BuildTaskService.ClaimedRow row = claimed.get();
        return ResponseEntity.ok(Map.of(
                "id", row.id().toString(),
                "attempt", row.attempt(),
                "payload", readPayload(row.payloadJson()),
                "leaseToken", row.leaseToken().toString(),
                "claimCount", row.claimCount()));
    }

    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable UUID id,
                                           @RequestHeader(name = "X-Lease-Token", required = false) String leaseToken) {
        presence.touchAcpOnHeartbeat(service.heartbeat(id, leaseToken));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/result")
    public ResponseEntity<Void> result(@PathVariable UUID id, @RequestBody BuildResult result,
                                        @RequestHeader(name = "X-Lease-Token", required = false) String leaseToken) {
        service.complete(id, leaseToken, result);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/plan-result")
    public ResponseEntity<Void> planResult(@PathVariable UUID id, @RequestBody PlanConsultationResultRequest request,
                                            @RequestHeader(name = "X-Lease-Token", required = false) String leaseToken) {
        service.completePlan(id, leaseToken, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<Void> fail(@PathVariable UUID id, @RequestBody FailRequest request,
                                      @RequestHeader(name = "X-Lease-Token", required = false) String leaseToken) {
        service.fail(id, leaseToken, request.message());
        return ResponseEntity.ok().build();
    }

    private Map<String, Object> readPayload(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to parse stored build task payload", e);
        }
    }
}
