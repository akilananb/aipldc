package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.persistence.ApprovalEntity;
import ai.pdlc.controlplane.persistence.ApprovalRepository;
import ai.pdlc.controlplane.persistence.ArtifactEntity;
import ai.pdlc.controlplane.persistence.ArtifactRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.review.ReviewTrailService;
import ai.pdlc.controlplane.temporal.WorkflowStubs;
import ai.pdlc.controlplane.web.dto.ApproveRequest;
import ai.pdlc.controlplane.web.dto.ItemDetailDto;
import ai.pdlc.controlplane.web.dto.ItemSummaryDto;
import ai.pdlc.controlplane.web.dto.ReviewStateDto;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.review.ReviewMdWriter;
import ai.pdlc.core.workflow.FeatureWorkflow;
import ai.pdlc.core.workflow.ReviewState;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/items")
public class ItemsController {

    private final WorkItemRepository workItems;
    private final ArtifactRepository artifacts;
    private final ApprovalRepository approvalOps;
    private final BoardPort board;
    private final PdlcConfig pdlcConfig;
    private final WorkflowStubs workflowStubs;
    private final IdentityResolver identityResolver;
    private final ReviewTrailService reviewTrail;

    public ItemsController(WorkItemRepository workItems, ArtifactRepository artifacts,
                            ApprovalRepository approvalOps, BoardPort board, PdlcConfig pdlcConfig,
                            WorkflowStubs workflowStubs, IdentityResolver identityResolver, ReviewTrailService reviewTrail) {
        this.workItems = workItems;
        this.artifacts = artifacts;
        this.approvalOps = approvalOps;
        this.board = board;
        this.pdlcConfig = pdlcConfig;
        this.workflowStubs = workflowStubs;
        this.identityResolver = identityResolver;
        this.reviewTrail = reviewTrail;
    }

    @GetMapping
    public List<ItemSummaryDto> list() {
        return workItems.findAllByOrderByUpdatedAtDesc().stream()
                .map(row -> new ItemSummaryDto(row.id(), row.boardId(), row.kind(),
                        safeTitle(row), row.canonicalState(), row.updatedAt(), row.parentId()))
                .toList();
    }

    @GetMapping("/{id}")
    public ItemDetailDto get(@PathVariable UUID id) {
        WorkItemEntity row = requireItem(id);
        WorkItem item = board.getItem(new WorkItemRef(row.profile(), row.boardId()));
        ArtifactEntity latest = artifacts.findByWorkItemIdOrderByVersionDesc(id).stream().findFirst().orElse(null);
        ReviewStateDto gate = queryGate(row);
        return new ItemDetailDto(row.id(), row.profile(), row.boardId(), row.kind(), item.title(), item.description(),
                row.canonicalState(), latest == null ? null : latest.version(), latest == null ? null : latest.contentHash(),
                gate, row.parentId());
    }

    @GetMapping(value = "/{id}/review-md", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> reviewMd(@PathVariable UUID id) {
        WorkItemEntity row = requireItem(id);
        if (row.specChangePath() == null) {
            return ResponseEntity.ok("");
        }
        String content = reviewTrail.readReviewMd(new WorkItemRef(row.profile(), row.boardId()), row.specChangePath());
        return ResponseEntity.ok(content);
    }

    @GetMapping("/{id}/board-comments")
    public List<ai.pdlc.core.domain.Comment> boardComments(@PathVariable UUID id) {
        WorkItemEntity row = requireItem(id);
        return board.listComments(new WorkItemRef(row.profile(), row.boardId()));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ReviewStateDto> approve(@PathVariable UUID id, @RequestBody ApproveRequest request, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        var gate1 = pdlcConfig.profile(story.profile()).gate("G1");
        if (!gate1.roles().contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not a gate 1 checker");
        }

        ArtifactEntity latest = artifacts.findByWorkItemIdOrderByVersionDesc(id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No artifact for item " + id));

        Approval approval = new Approval(identity.user(), identity.role(), "story", latest.version(), latest.contentHash(), Instant.now());
        WorkItemRef featureRef = new WorkItemRef(story.profile(), story.parentId());
        FeatureWorkflow stub = workflowStubs.featureWorkflow(featureRef);
        stub.approve(approval);

        ReviewState state = stub.state();
        if (state.approvals().containsKey(identity.role())
                && state.approvals().get(identity.role()).who().equals(identity.user())
                && state.version() == latest.version()) {
            approvalOps.save(ApprovalEntity.newRow(latest.id(), latest.version(), latest.contentHash(),
                    identity.user(), identity.role(), "story", OffsetDateTime.now()));
            reviewTrail.appendReviewMd(new WorkItemRef(story.profile(), story.boardId()), story.specChangePath(),
                    ReviewMdWriter.approveBlock(identity.role(), latest.version(), OffsetDateTime.now(), request.note() == null ? "" : request.note()));
            reviewTrail.appendReviewEvent(story.id(), "approve",
                    Map.of("who", identity.user(), "role", identity.role(), "version", latest.version()));
        }

        return ResponseEntity.ok(ReviewStateDto.from(state));
    }

    @PostMapping("/{id}/request-changes")
    public ResponseEntity<ReviewStateDto> requestChanges(@PathVariable UUID id, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        WorkItemRef featureRef = new WorkItemRef(story.profile(), story.parentId());
        FeatureWorkflow stub = workflowStubs.featureWorkflow(featureRef);
        stub.requestChanges(identity.user());
        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    private WorkItemEntity requireItem(UUID id) {
        return workItems.findById(id).orElseThrow(() -> new NotFoundException("No work item " + id));
    }

    private String safeTitle(WorkItemEntity row) {
        try {
            return board.getItem(new WorkItemRef(row.profile(), row.boardId())).title();
        } catch (RuntimeException e) {
            return "(unavailable)";
        }
    }

    private ReviewStateDto queryGate(WorkItemEntity row) {
        if (!"story".equals(row.kind()) || row.parentId() == null) {
            return null;
        }
        try {
            FeatureWorkflow stub = workflowStubs.featureWorkflow(new WorkItemRef(row.profile(), row.parentId()));
            return ReviewStateDto.from(stub.state());
        } catch (RuntimeException notRunning) {
            return null;
        }
    }
}
