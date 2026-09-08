package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.persistence.ApprovalEntity;
import ai.pdlc.controlplane.persistence.ApprovalRepository;
import ai.pdlc.controlplane.persistence.ArtifactEntity;
import ai.pdlc.controlplane.persistence.ArtifactRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.persistence.QualityReportEntity;
import ai.pdlc.controlplane.persistence.QualityReportRepository;
import ai.pdlc.controlplane.review.ReviewTrailService;
import ai.pdlc.controlplane.temporal.WorkflowStubs;
import ai.pdlc.controlplane.web.dto.ApproveRequest;
import ai.pdlc.controlplane.web.dto.GrillAnswerRequest;
import ai.pdlc.controlplane.web.dto.GrillQuestionsDto;
import ai.pdlc.controlplane.web.dto.ItemDetailDto;
import ai.pdlc.controlplane.web.dto.ItemSummaryDto;
import ai.pdlc.controlplane.web.dto.ReviewStateDto;
import ai.pdlc.controlplane.web.dto.QualityReportDto;
import ai.pdlc.controlplane.web.dto.SpecDocsDto;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.CommentRef;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.review.ReviewMdWriter;
import ai.pdlc.core.workflow.BoardCommentEvent;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/items")
public class ItemsController {

    private static final Pattern AREA_PATTERN = Pattern.compile("Area:\\s*([^\\s·]+)");

    private final WorkItemRepository workItems;
    private final ArtifactRepository artifacts;
    private final ApprovalRepository approvalOps;
    private final QualityReportRepository qualityReports;
    private final BoardPort board;
    private final PdlcConfig pdlcConfig;
    private final WorkflowStubs workflowStubs;
    private final IdentityResolver identityResolver;
    private final ReviewTrailService reviewTrail;
    private final RepoPort repo;

    public ItemsController(WorkItemRepository workItems, ArtifactRepository artifacts,
                            ApprovalRepository approvalOps, QualityReportRepository qualityReports, BoardPort board,
                            PdlcConfig pdlcConfig, WorkflowStubs workflowStubs, IdentityResolver identityResolver,
                            ReviewTrailService reviewTrail, RepoPort repo) {
        this.workItems = workItems;
        this.artifacts = artifacts;
        this.approvalOps = approvalOps;
        this.qualityReports = qualityReports;
        this.board = board;
        this.pdlcConfig = pdlcConfig;
        this.workflowStubs = workflowStubs;
        this.identityResolver = identityResolver;
        this.reviewTrail = reviewTrail;
        this.repo = repo;
    }

    @GetMapping
    public List<ItemSummaryDto> list() {
        return workItems.findAllByOrderByUpdatedAtDesc().stream()
                .map(row -> new ItemSummaryDto(row.id(), row.boardId(), row.kind(),
                        safeTitle(row), row.canonicalState(), row.updatedAt(), row.parentId(), latestQualityVerdict(row.id())))
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
                gate, row.parentId(), latestQualityVerdict(row.id()));
    }

    @GetMapping("/{id}/quality")
    public QualityReportDto quality(@PathVariable UUID id) {
        WorkItemEntity row = requireItem(id);
        QualityReportEntity latest = qualityReports.findByWorkItemIdOrderByCreatedAtDesc(row.id()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No quality report for item " + id));
        return new QualityReportDto(latest.verdict(), latest.score(), latest.reportMd(), latest.version(), latest.createdAt().toString());
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

    @GetMapping("/{id}/spec-docs")
    public SpecDocsDto specDocs(@PathVariable UUID id) {
        WorkItemEntity row = requireItem(id);
        WorkItemEntity storyRow = switch (row.kind()) {
            case "story" -> row;
            case "task" -> workItems.findByProfileAndBoardId(row.profile(), row.parentId())
                    .orElseThrow(() -> new NotFoundException("No parent story for task " + id));
            default -> throw new NotFoundException("No spec docs for kind " + row.kind());
        };

        String slug = storyRow.specChangePath();
        String proposalMd = null;
        String specMd = null;
        String tasksMd = null;
        if (slug != null) {
            String defaultBranch = pdlcConfig.profile(row.profile()).repo().defaultBranch();
            proposalMd = readOrNull(defaultBranch, slug + "/proposal.md");
            tasksMd = readOrNull(defaultBranch, slug + "/tasks.md");
            Matcher areaMatch = proposalMd == null ? null : AREA_PATTERN.matcher(proposalMd);
            String area = areaMatch != null && areaMatch.find() ? areaMatch.group(1) : "default";
            specMd = readOrNull(defaultBranch, slug + "/specs/" + area + "/spec.md");
        }

        List<SpecDocsDto.DocApproval> approvals = artifacts.findByWorkItemIdOrderByVersionDesc(storyRow.id()).stream()
                .flatMap(a -> approvalOps.findByArtifactIdAndVersion(a.id(), a.version()).stream())
                .map(e -> new SpecDocsDto.DocApproval(e.authorSub(), e.role(), e.version(), e.at()))
                .toList();

        return new SpecDocsDto(storyRow.id(), safeTitle(storyRow), slug, proposalMd, specMd, tasksMd, approvals);
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

        WorkItemRef featureRef = new WorkItemRef(story.profile(), story.parentId());
        FeatureWorkflow stub = workflowStubs.featureWorkflow(featureRef);

        ReviewState pre = stub.state();
        if (pre.activeStoryBoardId() != null && !pre.activeStoryBoardId().equals(story.boardId())) {
            throw new ConflictException("Story is queued; the previous story must finish first");
        }
        String qualityVerdict = latestQualityVerdict(story.id());
        if (qualityVerdict != null && !"passed".equals(qualityVerdict)) {
            throw new ConflictException("Quality evaluation has not passed for this story");
        }

        Approval approval = new Approval(identity.user(), identity.role(), "story", latest.version(), latest.contentHash(), Instant.now());
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

        ReviewState pre = stub.state();
        if (pre.activeStoryBoardId() != null && !pre.activeStoryBoardId().equals(story.boardId())) {
            throw new ConflictException("Story is queued; the previous story must finish first");
        }

        stub.requestChanges(identity.user());
        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    @GetMapping("/{id}/grill")
    public GrillQuestionsDto grill(@PathVariable UUID id) {
        WorkItemEntity row = requireItem(id);
        if (!"feature".equals(row.kind())) {
            throw new NotFoundException("No clarification questions for kind " + row.kind());
        }
        GrillHandoff grill;
        try {
            grill = workflowStubs.featureWorkflow(new WorkItemRef(row.profile(), row.boardId())).grill();
        } catch (RuntimeException notRunning) {
            throw new NotFoundException("No running workflow for item " + id);
        }
        return GrillQuestionsDto.from(grill);
    }

    @PostMapping("/{id}/grill/{questionId}/answer")
    public ResponseEntity<Void> answerGrillQuestion(@PathVariable UUID id, @PathVariable String questionId,
                                                      @RequestBody GrillAnswerRequest request, HttpServletRequest httpRequest) {
        if (request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("Answer text is required");
        }
        submitGrillReply(id, questionId, request.text().strip(), httpRequest);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/grill/{questionId}/park")
    public ResponseEntity<Void> parkGrillQuestion(@PathVariable UUID id, @PathVariable String questionId, HttpServletRequest httpRequest) {
        submitGrillReply(id, questionId, "park", httpRequest);
        return ResponseEntity.noContent().build();
    }

    /** Answers or parks one grill/PO-follow-up question: {@code PO}/{@code SquadLead} only (the G1
     * roles - playbook §1 "Human owner"); posts {@code <id>: <body>} as a board comment and signals
     * the workflow directly rather than relying on a webhook echo (harmless if one also arrives -
     * step 4 below ignores a non-{@code OPEN} question). */
    private void submitGrillReply(UUID id, String questionId, String body, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity row = requireItem(id);
        if (!"feature".equals(row.kind())) {
            throw new NotFoundException("No clarification questions for kind " + row.kind());
        }
        var gate1 = pdlcConfig.profile(row.profile()).gate("G1");
        if (!gate1.roles().contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " cannot answer clarification questions");
        }

        WorkItemRef itemRef = new WorkItemRef(row.profile(), row.boardId());
        FeatureWorkflow stub = workflowStubs.featureWorkflow(itemRef);
        GrillHandoff grill;
        try {
            grill = stub.grill();
        } catch (RuntimeException notRunning) {
            throw new NotFoundException("No running workflow for item " + id);
        }
        if (grill == null) {
            throw new ConflictException("Questions not posted yet");
        }
        GrillQuestion question = grill.questions().stream()
                .filter(q -> q.id().equalsIgnoreCase(questionId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("No question " + questionId));
        if (question.status() != GrillQuestion.Status.OPEN) {
            throw new ConflictException("Question " + questionId + " is already " + question.status().wireValue());
        }

        String line = question.id() + ": " + body;
        CommentRef ref = board.addComment(itemRef, line, identity.user());
        stub.commentAdded(new BoardCommentEvent(ref.id(), identity.user(), line));
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
            ReviewState state = stub.state();
            if (!row.boardId().equals(state.activeStoryBoardId())) {
                return null;
            }
            return ReviewStateDto.from(state);
        } catch (RuntimeException notRunning) {
            return null;
        }
    }

    private String latestQualityVerdict(UUID workItemId) {
        return qualityReports.findByWorkItemIdOrderByCreatedAtDesc(workItemId).stream().findFirst()
                .map(QualityReportEntity::verdict).orElse(null);
    }

    private String readOrNull(String branch, String path) {
        try {
            return repo.readFile(branch, path);
        } catch (RuntimeException notFound) {
            return null;
        }
    }
}
