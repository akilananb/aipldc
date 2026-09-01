package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.persistence.PrEntity;
import ai.pdlc.controlplane.persistence.PrRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.temporal.WorkflowStubs;
import ai.pdlc.controlplane.web.dto.ApproveRequest;
import ai.pdlc.controlplane.web.dto.ReviewStateDto;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.domain.Anchor;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.workflow.FeatureWorkflow;
import ai.pdlc.core.workflow.ReviewState;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Gate 2 (PR review) approve/request-changes — build-order phase 3. Reuses the exact same
 * {@link FeatureWorkflow#approve}/{@link FeatureWorkflow#requestChanges} signals gate 1 uses
 * (tech-stack §3.1: every gate reuses this shape), scoped by {@code stage: "pr"} and gate
 * {@code G2}'s roles. {@code id} is the story's work-item id — one PR per story (build-order
 * phase 3's shared branch), so no separate PR entity id is needed on the wire.
 */
@RestController
@RequestMapping("/api/items")
public class PrController {

    private final WorkItemRepository workItems;
    private final PrRepository prs;
    private final RepoPort repo;
    private final PdlcConfig pdlcConfig;
    private final WorkflowStubs workflowStubs;
    private final IdentityResolver identityResolver;

    public PrController(WorkItemRepository workItems, PrRepository prs, RepoPort repo, PdlcConfig pdlcConfig,
                         WorkflowStubs workflowStubs, IdentityResolver identityResolver) {
        this.workItems = workItems;
        this.prs = prs;
        this.repo = repo;
        this.pdlcConfig = pdlcConfig;
        this.workflowStubs = workflowStubs;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/{id}/pr/approve")
    public ResponseEntity<ReviewStateDto> approve(@PathVariable UUID id, @RequestBody ApproveRequest request, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        var gate2 = pdlcConfig.profile(story.profile()).gate("G2");
        if (!gate2.roles().contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not a gate 2 checker");
        }

        FeatureWorkflow stub = workflowStubs.featureWorkflow(new WorkItemRef(story.profile(), story.parentId()));
        ReviewState before = stub.state();
        String contentHash = prs.findByWorkItemId(story.id())
                .map(PrEntity::prId)
                .map(prId -> Anchor.hash(repo.getDiff(prId).unifiedDiff()))
                .orElse("n/a");

        Approval approval = new Approval(identity.user(), identity.role(), "pr", before.version(), contentHash, Instant.now());
        stub.approve(approval);

        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    @PostMapping("/{id}/pr/request-changes")
    public ResponseEntity<ReviewStateDto> requestChanges(@PathVariable UUID id, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        FeatureWorkflow stub = workflowStubs.featureWorkflow(new WorkItemRef(story.profile(), story.parentId()));
        stub.requestChanges(identity.user());
        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    private WorkItemEntity requireItem(UUID id) {
        return workItems.findById(id).orElseThrow(() -> new NotFoundException("No work item " + id));
    }
}
