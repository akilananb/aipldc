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
import ai.pdlc.controlplane.persistence.RunEntity;
import ai.pdlc.controlplane.persistence.RunRepository;
import ai.pdlc.controlplane.review.ReviewTrailService;
import ai.pdlc.controlplane.temporal.WorkflowStubs;
import ai.pdlc.controlplane.web.dto.ApproveRequest;
import ai.pdlc.controlplane.web.dto.GrillAnswerRequest;
import ai.pdlc.controlplane.web.dto.GrillQuestionsDto;
import ai.pdlc.controlplane.web.dto.ItemDetailDto;
import ai.pdlc.controlplane.web.dto.ItemSummaryDto;
import ai.pdlc.controlplane.web.dto.ReviewStateDto;
import ai.pdlc.controlplane.web.dto.QualityReportDto;
import ai.pdlc.controlplane.web.dto.AgentRunDto;
import ai.pdlc.controlplane.web.dto.SpecDocsDto;
import ai.pdlc.controlplane.config.PortRegistry;
import ai.pdlc.core.config.ProjectDirectory;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final RunRepository runs;
    private final PortRegistry ports;
    private final ProjectDirectory projects;
    private final WorkflowStubs workflowStubs;
    private final IdentityResolver identityResolver;
    private final ReviewTrailService reviewTrail;
    private final ai.pdlc.controlplane.demo.DemoSnapshotService demoSnapshots;
    private final ai.pdlc.controlplane.temporal.BuildTaskService buildTasks;
    private final ai.pdlc.controlplane.temporal.AgentPresenceService presence;

    public ItemsController(WorkItemRepository workItems, ArtifactRepository artifacts,
                            ApprovalRepository approvalOps, QualityReportRepository qualityReports, RunRepository runs,
                            PortRegistry ports, ProjectDirectory projects, WorkflowStubs workflowStubs, IdentityResolver identityResolver,
                            ReviewTrailService reviewTrail, ai.pdlc.controlplane.demo.DemoSnapshotService demoSnapshots,
                            ai.pdlc.controlplane.temporal.BuildTaskService buildTasks, ai.pdlc.controlplane.temporal.AgentPresenceService presence) {
        this.workItems = workItems;
        this.artifacts = artifacts;
        this.approvalOps = approvalOps;
        this.qualityReports = qualityReports;
        this.runs = runs;
        this.ports = ports;
        this.projects = projects;
        this.workflowStubs = workflowStubs;
        this.identityResolver = identityResolver;
        this.reviewTrail = reviewTrail;
        this.demoSnapshots = demoSnapshots;
        this.buildTasks = buildTasks;
        this.presence = presence;
    }

    private static ai.pdlc.controlplane.web.dto.DemoSnapshotDto snapshotDto(ai.pdlc.controlplane.demo.DemoSnapshotEntity s) {
        return s == null ? null
                : new ai.pdlc.controlplane.web.dto.DemoSnapshotDto(s.snapshotKey(), s.label(), s.ordinal(), s.sourceRef(), s.replay());
    }

    @GetMapping
    public List<ItemSummaryDto> list() {
        Map<UUID, AgentRunDto> active = activeRuns();
        Map<UUID, ai.pdlc.controlplane.demo.DemoSnapshotEntity> snapshotIndex = demoSnapshots.findAllIndexed();
        return workItems.findAllByOrderByUpdatedAtDesc().stream()
                .map(row -> new ItemSummaryDto(row.id(), row.profile(), row.boardId(), row.kind(),
                        safeTitle(row), row.canonicalState(), row.updatedAt(), row.parentId(), latestQualityVerdict(row.id()),
                        active.get(row.id()), snapshotDto(snapshotIndex.get(row.id()))))
                .toList();
    }

    @GetMapping("/{id}")
    public ItemDetailDto get(@PathVariable UUID id) {
        WorkItemEntity row = requireItem(id);
        WorkItem item = ports.board(row.profile()).getItem(new WorkItemRef(row.profile(), row.boardId()));
        ArtifactEntity latest = artifacts.findByWorkItemIdOrderByVersionDesc(id).stream().findFirst().orElse(null);
        ReviewStateDto gate = queryGate(row);
        AgentRunDto activeRun = activeRuns().get(id);
        return new ItemDetailDto(row.id(), row.profile(), row.boardId(), row.kind(), item.title(), item.description(),
                row.canonicalState(), latest == null ? null : latest.version(), latest == null ? null : latest.contentHash(),
                gate, row.parentId(), latestQualityVerdict(row.id()), activeRun, snapshotDto(demoSnapshots.find(id).orElse(null)),
                agentWork(row, activeRun));
    }


    @GetMapping("/{id}/activity")
    public List<AgentRunDto> activity(@PathVariable UUID id) {
        requireItem(id);
        OffsetDateTime now = OffsetDateTime.now();
        return runs.findByWorkItemIdOrderByCreatedAtDesc(id).stream().limit(100)
                .map(r -> AgentRunDto.from(r, now))
                .toList();
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
        java.util.Optional<ai.pdlc.controlplane.demo.DemoSnapshotEntity> snap = demoSnapshots.find(id);
        String content = snap.isPresent() && snap.get().gitRef() != null
                ? reviewTrail.readReviewMd(new WorkItemRef(row.profile(), row.boardId()), row.specChangePath(), snap.get().gitRef())
                : reviewTrail.readReviewMd(new WorkItemRef(row.profile(), row.boardId()), row.specChangePath());
        return ResponseEntity.ok(content);
    }

    @GetMapping("/{id}/board-comments")
    public List<ai.pdlc.core.domain.Comment> boardComments(@PathVariable UUID id) {
        WorkItemEntity row = requireItem(id);
        return ports.board(row.profile()).listComments(new WorkItemRef(row.profile(), row.boardId()));
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
            java.util.Optional<ai.pdlc.controlplane.demo.DemoSnapshotEntity> snap = demoSnapshots.find(storyRow.id());
            String readRef = snap.map(ai.pdlc.controlplane.demo.DemoSnapshotEntity::gitRef)
                    .orElseGet(() -> projects.project(row.profile()).repo().defaultBranch());
            proposalMd = readOrNull(row.profile(), readRef, slug + "/proposal.md");
            tasksMd = readOrNull(row.profile(), readRef, slug + "/tasks.md");
            Matcher areaMatch = proposalMd == null ? null : AREA_PATTERN.matcher(proposalMd);
            String area = areaMatch != null && areaMatch.find() ? areaMatch.group(1) : "default";
            specMd = readOrNull(row.profile(), readRef, slug + "/specs/" + area + "/spec.md");
        }

        List<SpecDocsDto.DocApproval> approvals = artifacts.findByWorkItemIdOrderByVersionDesc(storyRow.id()).stream()
                .flatMap(a -> approvalOps.findByArtifactIdAndVersion(a.id(), a.version()).stream())
                .map(e -> new SpecDocsDto.DocApproval(e.authorSub(), e.role(), e.version(), e.at()))
                .toList();

        return new SpecDocsDto(storyRow.id(), safeTitle(storyRow), slug, proposalMd, specMd, tasksMd, approvals);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ReviewStateDto> approve(@PathVariable UUID id, @RequestBody ApproveRequest request, HttpServletRequest httpRequest) {
        demoSnapshots.requireWritable(id);
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        var gate1 = projects.project(story.profile()).gate("G1");
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
        demoSnapshots.requireWritable(id);
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

    /** Retries the currently-blocked reasoning/LLM step for this item (see {@link
     * ReviewState#lastFailure} / {@code FeatureWorkflowImpl#reasoningStep}) — signals the same
     * workflow execution to resume exactly where it blocked, never a fresh restart. Works on
     * either a feature (pre-story failures: grill/po-draft) or a story (po-revise/quality/review/
     * release) item, unlike {@link #approve}/{@link #requestChanges} which are story-only. Gate-
     * role-restricted by {@link #gateForStep}, not open to any identity: retrying resumes real
     * pipeline work, so it needs the same authorization as the gate the step feeds into. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<ReviewStateDto> retry(@PathVariable UUID id, HttpServletRequest httpRequest) {
        demoSnapshots.requireWritable(id);
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity row = requireItem(id);
        WorkItemRef featureRef = "feature".equals(row.kind())
                ? new WorkItemRef(row.profile(), row.boardId())
                : new WorkItemRef(row.profile(), row.parentId());
        FeatureWorkflow stub = workflowStubs.featureWorkflow(featureRef);

        ReviewState state;
        try {
            state = stub.state();
        } catch (RuntimeException notRunning) {
            throw new NotFoundException("No running workflow for item " + id);
        }
        if (state.lastFailure() == null) {
            throw new ConflictException("Nothing is currently blocked for item " + id);
        }
        var gate = projects.project(row.profile()).gate(gateForStep(state.lastFailure().step()));
        if (!gate.roles().contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " cannot retry step " + state.lastFailure().step());
        }

        stub.retryStep(identity.user());
        return ResponseEntity.ok(ReviewStateDto.from(stub.state()));
    }

    /** Maps a {@link ai.pdlc.core.workflow.StepFailure#step()} name to the gate whose roles may
     * retry it: {@code release-draft} feeds gate 3 (release pack), {@code review-story} feeds gate
     * 2 (PR review) — every earlier step (grill/po-draft/po-revise/evaluate-quality/plan-next-step/
     * plan-consult) happens before any gate-2/3 review has started, so gate 1 owns it. Pure/static
     * so it's directly unit-testable without a Spring context. */
    static String gateForStep(String step) {
        if (step != null && step.startsWith("release-")) {
            return "G3";
        }
        if ("review-story".equals(step)) {
            return "G2";
        }
        return "G1";
    }

    @GetMapping("/{id}/grill")
    public GrillQuestionsDto grill(@PathVariable UUID id) {
        WorkItemEntity row = requireItem(id);
        WorkItemEntity feature = clarificationOwner(row);
        java.util.Optional<ai.pdlc.controlplane.demo.DemoSnapshotEntity> snap = demoSnapshots.find(feature.id());
        if (snap.isPresent()) {
            String json = snap.get().grillJson();
            return json == null ? new GrillQuestionsDto(true, 0, List.of()) : readJson(json, GrillQuestionsDto.class);
        }
        FeatureWorkflow stub = workflowStubs.featureWorkflow(new WorkItemRef(feature.profile(), feature.boardId()));
        GrillHandoff grill;
        ReviewState state;
        int rounds;
        try {
            grill = stub.grill();
            state = stub.state();
            rounds = stub.grillRounds();
        } catch (RuntimeException notRunning) {
            throw new NotFoundException("No running workflow for item " + id);
        }
        return GrillQuestionsDto.from(grill, state.stage(), rounds);
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

    /** Reviewer proceeds past adaptive intake once at least two grill rounds have been posted
     * (enforced again, authoritatively, by {@link ai.pdlc.core.workflow.FeatureWorkflow#grillRounds()}
     * inside the workflow itself): parks every remaining open question and hands off to the PO
     * agent. Posts a plain audit comment (no {@code <id>:} marker, so it never triggers a
     * commentAdded fold) and signals {@code proceedToStory} directly. */
    @PostMapping("/{id}/grill/proceed")
    public ResponseEntity<Void> proceedGrill(@PathVariable UUID id, HttpServletRequest httpRequest) {
        demoSnapshots.requireWritable(id);
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity row = requireItem(id);
        WorkItemEntity feature = clarificationOwner(row);

        WorkItemRef featureRef = new WorkItemRef(feature.profile(), feature.boardId());
        FeatureWorkflow stub = workflowStubs.featureWorkflow(featureRef);
        GrillHandoff grill;
        int rounds;
        try {
            grill = stub.grill();
            rounds = stub.grillRounds();
        } catch (RuntimeException notRunning) {
            throw new NotFoundException("No running workflow for item " + id);
        }
        if (grill == null) {
            throw new ConflictException("Questions not posted yet");
        }
        if (rounds < 2) {
            throw new ConflictException("Proceed is available after two grill rounds");
        }

        var gate1 = projects.project(row.profile()).gate("G1");
        if (!gate1.roles().contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " cannot proceed intake");
        }

        WorkItemRef itemRef = new WorkItemRef(row.profile(), row.boardId());
        ports.board(row.profile()).addComment(itemRef, "Proceeding to story drafting; remaining open questions parked.", identity.user());
        stub.proceedToStory(identity.user());
        return ResponseEntity.noContent().build();
    }

    /** The item whose grill/build-loop clarification questions {@code row} shares: a feature owns
     * its own; a story defers to its parent feature (the workflow that actually holds the {@link
     * GrillHandoff}, including any {@code h*} build-loop questions posted while that story is
     * building). */
    private WorkItemEntity clarificationOwner(WorkItemEntity row) {
        if ("feature".equals(row.kind())) {
            return row;
        }
        if ("story".equals(row.kind()) && row.parentId() != null) {
            return workItems.findByProfileAndBoardId(row.profile(), row.parentId())
                    .orElseThrow(() -> new NotFoundException("No parent feature for story " + row.id()));
        }
        throw new NotFoundException("No clarification questions for kind " + row.kind());
    }

    /** Answers or parks one grill/PO-follow-up/build-loop question: grill/PO-follow-up questions
     * are gate 1 roles only (playbook §1 "Human owner"); a build-loop {@code h*} question also
     * accepts a gate 2 role (the checker actually watching the build, per playbook §4's human-input
     * path). Posts {@code <id>: <body>} as a board comment on the item the request was made against
     * (the story, when called with a story id) and signals the feature workflow directly rather
     * than relying on a webhook echo (harmless if one also arrives - step 4 below ignores a
     * non-{@code OPEN} question). */
    private void submitGrillReply(UUID id, String questionId, String body, HttpServletRequest httpRequest) {
        demoSnapshots.requireWritable(id);
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity row = requireItem(id);
        WorkItemEntity feature = clarificationOwner(row);

        WorkItemRef featureRef = new WorkItemRef(feature.profile(), feature.boardId());
        FeatureWorkflow stub = workflowStubs.featureWorkflow(featureRef);
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

        Set<String> allowed = new LinkedHashSet<>(projects.project(row.profile()).gate("G1").roles());
        if (question.askedByBuildLoop()) {
            allowed.addAll(projects.project(row.profile()).gate("G2").roles());
        }
        if (!allowed.contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " cannot answer clarification questions");
        }

        WorkItemRef itemRef = new WorkItemRef(row.profile(), row.boardId());
        String line = question.id() + ": " + body;
        CommentRef ref = ports.board(row.profile()).addComment(itemRef, line, identity.user());
        stub.commentAdded(new BoardCommentEvent(ref.id(), identity.user(), line));
    }

    private WorkItemEntity requireItem(UUID id) {
        return workItems.findById(id).orElseThrow(() -> new NotFoundException("No work item " + id));
    }

    private String safeTitle(WorkItemEntity row) {
        try {
            return ports.board(row.profile()).getItem(new WorkItemRef(row.profile(), row.boardId())).title();
        } catch (RuntimeException e) {
            return "(unavailable)";
        }
    }

    private ReviewStateDto queryGate(WorkItemEntity row) {
        if ("feature".equals(row.kind())) {
            try {
                FeatureWorkflow stub = workflowStubs.featureWorkflow(new WorkItemRef(row.profile(), row.boardId()));
                return ReviewStateDto.from(stub.state());
            } catch (RuntimeException notRunning) {
                return null;
            }
        }
        if (!"story".equals(row.kind()) || row.parentId() == null) {
            return null;
        }
        java.util.Optional<ai.pdlc.controlplane.demo.DemoSnapshotEntity> snap = demoSnapshots.find(row.id());
        if (snap.isPresent()) {
            String json = snap.get().gateJson();
            return json == null ? null : readJson(json, ReviewStateDto.class);
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

    /** One live run per work item (newest wins, rows are newest-first), keyed by work item id. */
    private Map<UUID, AgentRunDto> activeRuns() {
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, AgentRunDto> active = new LinkedHashMap<>();
        for (RunEntity r : runs.findByOutcomeOrderByCreatedAtDesc("running")) {
            AgentRunDto dto = AgentRunDto.from(r, now);
            if ("running".equals(dto.status())) {
                active.putIfAbsent(r.workItemId(), dto);
            }
        }
        return active;
    }

    /** Live plan/build status for a story item - see {@link ItemDetailDto.AgentWorkDto}. Null for
     * every non-story item and for a story with nothing currently in flight. */
    private ItemDetailDto.AgentWorkDto agentWork(WorkItemEntity row, AgentRunDto activeRun) {
        if (!"story".equals(row.kind())) {
            return null;
        }
        java.time.Instant now = java.time.Instant.now();
        int workersOnline = (int) presence.list().stream()
                .filter(p -> "acp".equals(p.kind()))
                .filter(p -> ai.pdlc.controlplane.temporal.AgentPresenceService.onlineAt(p.lastSeenAt(), now))
                .count();
        if (activeRun != null && "plan".equals(activeRun.agent())) {
            return new ItemDetailDto.AgentWorkDto("reasoning", "plan", null, null, null, null, workersOnline);
        }
        return buildTasks.latestOpenTask(row.profile(), row.boardId())
                .map(open -> {
                    String phase = "pending".equals(open.state()) ? "waiting-for-worker" : "running";
                    String kind = "plan".equals(open.taskId()) ? "plan" : "build";
                    return new ItemDetailDto.AgentWorkDto(phase, kind, open.taskId(), open.round(), open.claimedBy(),
                            open.since(), workersOnline);
                })
                .orElse(null);
    }

    private String readOrNull(String profile, String branch, String path) {
        try {
            return ports.primaryRepo(profile).readFile(branch, path);
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SNAPSHOT_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static <T> T readJson(String json, Class<T> type) {
        try {
            return SNAPSHOT_JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt demo snapshot JSON for " + type.getSimpleName(), e);
        }
    }
}
