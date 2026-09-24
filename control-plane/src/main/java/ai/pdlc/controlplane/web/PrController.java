package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.config.PortRegistry;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.persistence.PrEntity;
import ai.pdlc.controlplane.persistence.PrRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.temporal.WorkflowStubs;
import ai.pdlc.controlplane.web.dto.ApproveRequest;
import ai.pdlc.controlplane.web.dto.ReviewStateDto;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.domain.Anchor;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.WorkItemRef;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gate 2 (PR review) approve/request-changes — build-order phase 3. Reuses the exact same
 * {@link FeatureWorkflow#approve}/{@link FeatureWorkflow#requestChanges} signals gate 1 uses
 * (tech-stack §3.1: every gate reuses this shape), scoped by {@code stage: "pr"} and gate
 * {@code G2}'s roles. {@code id} is the story's work-item id; a multi-repo story may have opened
 * more than one PR (one per repo it touched) — the approval content hash covers every PR's diff.
 */
@RestController
@RequestMapping("/api/items")
public class PrController {

    private final WorkItemRepository workItems;
    private final PrRepository prs;
    private final PortRegistry ports;
    private final ProjectDirectory projects;
    private final WorkflowStubs workflowStubs;
    private final IdentityResolver identityResolver;
    private final ai.pdlc.controlplane.demo.DemoSnapshotService demoSnapshots;

    public PrController(WorkItemRepository workItems, PrRepository prs, PortRegistry ports, ProjectDirectory projects,
                         WorkflowStubs workflowStubs, IdentityResolver identityResolver,
                         ai.pdlc.controlplane.demo.DemoSnapshotService demoSnapshots) {
        this.workItems = workItems;
        this.prs = prs;
        this.ports = ports;
        this.projects = projects;
        this.workflowStubs = workflowStubs;
        this.identityResolver = identityResolver;
        this.demoSnapshots = demoSnapshots;
    }

    @PostMapping("/{id}/pr/approve")
    public ResponseEntity<ReviewStateDto> approve(@PathVariable UUID id, @RequestBody ApproveRequest request, HttpServletRequest httpRequest) {
        demoSnapshots.requireWritable(id);
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        var gate2 = projects.project(story.profile()).gate("G2");
        if (!gate2.roles().contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not a gate 2 checker");
        }

        FeatureWorkflow stub = workflowStubs.featureWorkflow(new WorkItemRef(story.profile(), story.parentId()));
        ReviewState before = stub.state();
        String profile = story.profile();
        List<PrEntity> storyPrs = prs.findAllByWorkItemId(story.id());
        String contentHash = storyPrs.isEmpty() ? "n/a" : Anchor.hash(storyPrs.stream()
                .map(p -> ports.repo(profile, p.repoId()).getDiff(p.prId()).unifiedDiff())
                .collect(Collectors.joining("\n")));

        Approval approval = new Approval(identity.user(), identity.role(), "pr", before.version(), contentHash, Instant.now());
        stub.approve(approval);

        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    @PostMapping("/{id}/pr/request-changes")
    public ResponseEntity<ReviewStateDto> requestChanges(@PathVariable UUID id, HttpServletRequest httpRequest) {
        demoSnapshots.requireWritable(id);
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
