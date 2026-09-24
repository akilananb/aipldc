package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.temporal.FeatureWorkflowStarter;
import ai.pdlc.core.domain.WorkItemRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Demo status/live-start surface - plan step 5. {@code demo-live} is the separate, real,
 * non-snapshot feature DemoInitializer seeds at {@code new}: this controller only reports whether
 * the demo is enabled/initialized and starts its real {@link ai.pdlc.core.workflow.FeatureWorkflow}
 * on request - the first (and only) action in this whole feature that invokes the configured LLM.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final String LIVE_BOARD_ID = "demo-live";

    private final WorkItemRepository workItems;
    private final FeatureWorkflowStarter featureWorkflowStarter;
    private final String defaultProjectId;
    private final boolean demoEnabled;

    public DemoController(WorkItemRepository workItems, FeatureWorkflowStarter featureWorkflowStarter,
                           @Value("${pdlc.active-profile}") String defaultProjectId,
                           @Value("${pdlc.demo.enabled:false}") boolean demoEnabled) {
        this.workItems = workItems;
        this.featureWorkflowStarter = featureWorkflowStarter;
        this.defaultProjectId = defaultProjectId;
        this.demoEnabled = demoEnabled;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        if (!demoEnabled) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("enabled", false);
            body.put("liveItemId", null);
            return ResponseEntity.ok(body);
        }
        UUID liveItemId = findLiveItem().map(WorkItemEntity::id).orElse(null);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("enabled", true);
        body.put("liveItemId", liveItemId);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/live/start")
    public ResponseEntity<Map<String, UUID>> startLive() {
        if (!demoEnabled) {
            throw new NotFoundException("Demo is not enabled for this profile");
        }
        WorkItemEntity live = findLiveItem()
                .orElseThrow(() -> new ConflictException("Demo has not finished initializing yet; retry shortly"));
        // Idempotent: FeatureWorkflowStarter.start already handles WorkflowExecutionAlreadyStarted
        // as a no-op, so a repeated call here never rewrites progressed state or starts a second
        // workflow for the same item.
        featureWorkflowStarter.start(new WorkItemRef(live.profile(), live.boardId()));
        return ResponseEntity.ok(Map.of("itemId", live.id()));
    }

    private Optional<WorkItemEntity> findLiveItem() {
        return workItems.findByProfileAndBoardId(defaultProjectId, LIVE_BOARD_ID);
    }
}
