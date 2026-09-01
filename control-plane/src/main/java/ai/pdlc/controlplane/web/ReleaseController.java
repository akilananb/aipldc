package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.persistence.ReleaseDocumentEntity;
import ai.pdlc.controlplane.persistence.ReleaseDocumentRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.temporal.WorkflowStubs;
import ai.pdlc.controlplane.web.dto.ApproveRequest;
import ai.pdlc.controlplane.web.dto.ReleaseDocumentDto;
import ai.pdlc.controlplane.web.dto.ReviewStateDto;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.FeatureWorkflow;
import ai.pdlc.core.workflow.ReviewState;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Gate 3 (release pack) per-document sign/request-changes — tech-stack §3.4 "a document-level
 * sign is a signal with {@code doc_id}". Reuses the exact same {@link FeatureWorkflow#approve}/
 * {@link FeatureWorkflow#requestChanges} signals every other gate uses (tech-stack §3.1), scoped
 * by {@code stage: "release-pack:<doc-id>"} - see {@code FeatureWorkflowImpl#RELEASE_STAGE_PREFIX}.
 */
@RestController
@RequestMapping("/api/items")
public class ReleaseController {

    private static final String STAGE_PREFIX = "release-pack:";

    private final WorkItemRepository workItems;
    private final ReleaseDocumentRepository releaseDocuments;
    private final WorkflowStubs workflowStubs;
    private final IdentityResolver identityResolver;

    public ReleaseController(WorkItemRepository workItems, ReleaseDocumentRepository releaseDocuments,
                              WorkflowStubs workflowStubs, IdentityResolver identityResolver) {
        this.workItems = workItems;
        this.releaseDocuments = releaseDocuments;
        this.workflowStubs = workflowStubs;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/{id}/release")
    public ResponseEntity<List<ReleaseDocumentDto>> documents(@PathVariable UUID id) {
        WorkItemEntity story = requireItem(id);
        ReviewState state = featureWorkflow(story).state();
        List<ReleaseDocumentEntity> docs = releaseDocuments.findByStoryIdAndPackVersion(story.id(), state.version());
        return ResponseEntity.ok(docs.stream()
                .map(d -> ReleaseDocumentDto.from(d, state.approvals().containsKey(d.docId())))
                .toList());
    }

    @PostMapping("/{id}/release/{docId}/sign")
    public ResponseEntity<ReviewStateDto> sign(@PathVariable UUID id, @PathVariable String docId,
                                                @RequestBody ApproveRequest request, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        FeatureWorkflow stub = featureWorkflow(story);
        ReviewState before = stub.state();

        ReleaseDocumentEntity doc = releaseDocuments.findByStoryIdAndDocIdAndPackVersion(story.id(), docId, before.version())
                .orElseThrow(() -> new NotFoundException("No release document " + docId + " at pack version " + before.version()));
        if (!doc.checkerRole().equals(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not the checker for document " + docId);
        }

        Approval approval = new Approval(identity.user(), identity.role(), STAGE_PREFIX + docId,
                before.version(), doc.contentHash(), Instant.now());
        stub.approve(approval);

        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    @PostMapping("/{id}/release/request-changes")
    public ResponseEntity<ReviewStateDto> requestChanges(@PathVariable UUID id, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        FeatureWorkflow stub = featureWorkflow(story);
        stub.requestChanges(identity.user());
        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    private FeatureWorkflow featureWorkflow(WorkItemEntity story) {
        return workflowStubs.featureWorkflow(new WorkItemRef(story.profile(), story.parentId()));
    }

    private WorkItemEntity requireItem(UUID id) {
        return workItems.findById(id).orElseThrow(() -> new NotFoundException("No work item " + id));
    }
}
