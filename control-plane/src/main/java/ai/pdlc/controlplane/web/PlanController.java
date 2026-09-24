package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.review.ReviewTrailService;
import ai.pdlc.controlplane.temporal.WorkflowStubs;
import ai.pdlc.controlplane.web.dto.ApproveRequest;
import ai.pdlc.controlplane.web.dto.ReviewStateDto;
import ai.pdlc.controlplane.config.PortRegistry;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.domain.Anchor;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.CanonicalState;
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
import java.util.Map;
import java.util.UUID;

/**
 * Plan gate approve/request-changes: the task plan the Plan Agent breaks a story into must be
 * approved by a SquadLead before the build loop starts. Reuses the exact same {@link
 * FeatureWorkflow#approve}/{@link FeatureWorkflow#requestChanges} signals every other gate uses
 * (tech-stack §3.1: every gate reuses this shape), scoped by {@code stage: "plan"} and gate
 * {@code PLAN}'s roles. {@code id} is the story's work-item id, exactly like {@link PrController}.
 */
@RestController
@RequestMapping("/api/items")
public class PlanController {

    private final WorkItemRepository workItems;
    private final PortRegistry ports;
    private final ProjectDirectory projects;
    private final WorkflowStubs workflowStubs;
    private final IdentityResolver identityResolver;
    private final ReviewTrailService reviewTrail;
    private final ai.pdlc.controlplane.demo.DemoSnapshotService demoSnapshots;

    public PlanController(WorkItemRepository workItems, PortRegistry ports, ProjectDirectory projects,
                           WorkflowStubs workflowStubs, IdentityResolver identityResolver, ReviewTrailService reviewTrail,
                           ai.pdlc.controlplane.demo.DemoSnapshotService demoSnapshots) {
        this.workItems = workItems;
        this.ports = ports;
        this.projects = projects;
        this.workflowStubs = workflowStubs;
        this.identityResolver = identityResolver;
        this.reviewTrail = reviewTrail;
        this.demoSnapshots = demoSnapshots;
    }

    @PostMapping("/{id}/plan/approve")
    public ResponseEntity<ReviewStateDto> approve(@PathVariable UUID id, @RequestBody ApproveRequest request, HttpServletRequest httpRequest) {
        demoSnapshots.requireWritable(id);
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        var planGate = projects.project(story.profile()).gate("PLAN");
        if (!planGate.roles().contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not a plan checker");
        }

        FeatureWorkflow stub = workflowStubs.featureWorkflow(new WorkItemRef(story.profile(), story.parentId()));
        ReviewState before = stub.state();
        if (before.stage() != CanonicalState.PLANNED) {
            throw new ConflictException("Story is not awaiting plan approval");
        }

        String contentHash;
        try {
            String defaultBranch = projects.project(story.profile()).repo().defaultBranch();
            String tasksMd = ports.primaryRepo(story.profile()).readFile(defaultBranch, story.specChangePath() + "/tasks.md");
            contentHash = Anchor.hash(tasksMd);
        } catch (RuntimeException unreadable) {
            contentHash = "n/a";
        }

        Approval approval = new Approval(identity.user(), identity.role(), "plan", before.version(), contentHash, Instant.now());
        stub.approve(approval);
        reviewTrail.appendReviewEvent(story.id(), "plan-approve",
                Map.of("who", identity.user(), "role", identity.role(), "version", before.version()));

        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    @PostMapping("/{id}/plan/request-changes")
    public ResponseEntity<ReviewStateDto> requestChanges(@PathVariable UUID id, HttpServletRequest httpRequest) {
        demoSnapshots.requireWritable(id);
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        var planGate = projects.project(story.profile()).gate("PLAN");
        if (!planGate.roles().contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not a plan checker");
        }

        FeatureWorkflow stub = workflowStubs.featureWorkflow(new WorkItemRef(story.profile(), story.parentId()));
        if (stub.state().stage() != CanonicalState.PLANNED) {
            throw new ConflictException("Story is not awaiting plan approval");
        }
        stub.requestChanges(identity.user());
        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    private WorkItemEntity requireItem(UUID id) {
        return workItems.findById(id).orElseThrow(() -> new NotFoundException("No work item " + id));
    }
}
