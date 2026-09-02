package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.persistence.ArtifactEntity;
import ai.pdlc.controlplane.persistence.ArtifactRepository;
import ai.pdlc.controlplane.persistence.CommentEntity;
import ai.pdlc.controlplane.persistence.CommentRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.review.AgentMentions;
import ai.pdlc.controlplane.review.CommentReanchorer;
import ai.pdlc.controlplane.review.ReviewTrailService;
import ai.pdlc.controlplane.temporal.WorkflowStubs;
import ai.pdlc.controlplane.web.dto.ArtifactVersionDto;
import ai.pdlc.controlplane.web.dto.CommentDto;
import ai.pdlc.controlplane.web.dto.CommentRequest;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.domain.Anchor;
import ai.pdlc.core.domain.AgentMentionRequest;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.workflow.AgentMentionWorkflow;
import ai.pdlc.core.workflow.FeatureWorkflow;
import ai.pdlc.core.workflow.TaskQueues;
import ai.pdlc.core.review.ReviewMdWriter;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {id}} throughout is the {@code work_items} id; {@code artifacts} are addressed by
 * (work item, version) pairs, matching {@link ArtifactRepository#findByWorkItemIdAndVersion}.
 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactsController {

    private static final Pattern LINE_TARGET = Pattern.compile("^line:(\\d+)(?:-(\\d+))?$");
    private static final Pattern SCENARIO_TARGET = Pattern.compile("^scenario:(.+)$");

    private final WorkItemRepository workItems;
    private final ArtifactRepository artifacts;
    private final CommentRepository comments;
    private final RepoPort repo;
    private final CommentReanchorer reanchorer;
    private final ReviewTrailService reviewTrail;
    private final WorkflowStubs workflowStubs;
    private final IdentityResolver identityResolver;
    private final WorkflowClient workflowClient;
    private final PdlcConfig pdlcConfig;

    public ArtifactsController(WorkItemRepository workItems, ArtifactRepository artifacts, CommentRepository comments,
                                RepoPort repo, CommentReanchorer reanchorer, ReviewTrailService reviewTrail,
                                WorkflowStubs workflowStubs, IdentityResolver identityResolver,
                                WorkflowClient workflowClient, PdlcConfig pdlcConfig) {
        this.workItems = workItems;
        this.artifacts = artifacts;
        this.comments = comments;
        this.repo = repo;
        this.reanchorer = reanchorer;
        this.reviewTrail = reviewTrail;
        this.workflowStubs = workflowStubs;
        this.identityResolver = identityResolver;
        this.workflowClient = workflowClient;
        this.pdlcConfig = pdlcConfig;
    }

    @GetMapping("/{id}/versions/{v}")
    public ArtifactVersionDto version(@PathVariable UUID id, @PathVariable int v) {
        WorkItemEntity story = requireItem(id);
        ArtifactEntity artifact = artifacts.findByWorkItemIdAndVersion(id, v)
                .orElseThrow(() -> new NotFoundException("No version " + v + " for item " + id));

        String storyMarkdown = repo.readFile(artifact.gitRef(), story.specChangePath() + "/proposal.md");
        List<CommentReanchorer.Line> lines = reanchorer.index(storyMarkdown);

        List<CommentDto> commentDtos = comments.findByArtifactIdOrderByCreatedAt(artifact.id()).stream()
                .map(c -> {
                    CommentReanchorer.Anchored anchored = reanchorer.reanchor(c, lines);
                    return new CommentDto(c.id(), c.authorSub(), c.role(), targetOf(c, anchored.anchor()), c.text(), c.intent(),
                            c.blocking(), c.version(), c.resolvedInVersion(), c.agentReply(), anchored.anchor(), anchored.drifted(),
                            c.agentName(), c.agentResultMd(), c.agentResultStatus(), c.agentResultApprovedBy());
                })
                .toList();

        return new ArtifactVersionDto(v, artifact.contentHash(), storyMarkdown, commentDtos);
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<CommentDto> addComment(@PathVariable UUID id, @RequestBody CommentRequest request, HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        ArtifactEntity latest = artifacts.findByWorkItemIdOrderByVersionDesc(id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No artifact for item " + id));

        String storyMarkdown = repo.readFile(latest.gitRef(), story.specChangePath() + "/proposal.md");
        Anchor anchor = resolveAnchor(request.target(), storyMarkdown);
        String anchorJson = reanchorer.toAnchorJson(anchor);

        Optional<String> mention = AgentMentions.parse(request.text());
        CommentEntity newRow = CommentEntity.newRow(latest.id(), latest.version(), identity.user(),
                identity.role(), anchorJson, request.text(), request.intent(), request.blocking());
        if (mention.isPresent()) {
            newRow = newRow.withAgentRequest(mention.get());
        }
        CommentEntity saved = comments.save(newRow);

        WorkItemRef storyRef = new WorkItemRef(story.profile(), story.boardId());
        reviewTrail.appendReviewMd(storyRef, story.specChangePath(),
                ReviewMdWriter.commentBlock(identity.role(), request.target()
                        + (anchor != null && anchor.scenario() != null ? " (Scenario: " + anchor.scenario() + ")" : ""),
                        OffsetDateTime.now(), request.text()));
        reviewTrail.appendReviewEvent(story.id(), "comment",
                Map.of("who", identity.user(), "target", request.target(), "blocking", request.blocking()));

        Comment domainComment = new Comment(saved.id().toString(), identity.user(), identity.role(), "story",
                request.target(), request.text(), Comment.Intent.fromWire(request.intent()), request.blocking(), latest.version());
        WorkItemRef featureRef = new WorkItemRef(story.profile(), story.parentId());
        FeatureWorkflow stub = workflowStubs.featureWorkflow(featureRef);
        stub.comment(domainComment);

        if (mention.isPresent()) {
            startMentionWorkflow(story, saved, mention.get(), request);
        }

        return ResponseEntity.ok(new CommentDto(saved.id(), saved.authorSub(), saved.role(), request.target(), saved.text(),
                saved.intent(), saved.blocking(), saved.version(), saved.resolvedInVersion(), saved.agentReply(), anchor, false,
                saved.agentName(), saved.agentResultMd(), saved.agentResultStatus(), saved.agentResultApprovedBy()));
    }

    @PostMapping("/{id}/comments/{commentId}/approve-agent-result")
    public ResponseEntity<Void> approveAgentResult(@PathVariable UUID id, @PathVariable UUID commentId,
                                                    HttpServletRequest httpRequest) {
        Identity identity = identityResolver.resolve(httpRequest);
        WorkItemEntity story = requireItem(id);
        var gate1 = pdlcConfig.profile(story.profile()).gate("G1");
        if (!gate1.roles().contains(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not a gate 1 checker");
        }

        CommentEntity comment = comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("No comment " + commentId));
        ArtifactEntity artifact = artifacts.findById(comment.artifactId())
                .orElseThrow(() -> new NotFoundException("No artifact for comment " + commentId));
        if (!artifact.workItemId().equals(id)) {
            throw new NotFoundException("No comment " + commentId + " for item " + id);
        }
        if (!CommentEntity.AGENT_PENDING.equals(comment.agentResultStatus())) {
            throw new ConflictException("Agent result for comment " + commentId + " is not pending approval");
        }

        comments.save(comment.withAgentApproved(identity.user()));

        WorkItemRef storyRef = new WorkItemRef(story.profile(), story.boardId());
        Anchor anchor = reanchorer.parseAnchor(comment.anchorJson());
        reviewTrail.appendReviewMd(storyRef, story.specChangePath(),
                ReviewMdWriter.agentResultBlock(comment.agentName(), targetOf(comment, anchor), OffsetDateTime.now(),
                        identity.user(), comment.agentResultMd()));
        reviewTrail.appendReviewEvent(story.id(), "agent-result-approved",
                Map.of("commentId", commentId.toString(), "agent", comment.agentName(), "approvedBy", identity.user()));

        return ResponseEntity.noContent().build();
    }

    private void startMentionWorkflow(WorkItemEntity story, CommentEntity saved, String agentName, CommentRequest request) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId("mention-" + story.profile() + "-" + saved.id())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
        AgentMentionWorkflow wf = workflowClient.newWorkflowStub(AgentMentionWorkflow.class, options);
        try {
            WorkflowClient.start(wf::run, new AgentMentionRequest(story.profile(), story.id().toString(),
                    saved.id().toString(), agentName, request.text(), request.target(), story.specChangePath()));
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // webhook-replay-style idempotency; the running workflow will fill the result
        }
    }

    private WorkItemEntity requireItem(UUID id) {
        return workItems.findById(id).orElseThrow(() -> new NotFoundException("No work item " + id));
    }

    private static String targetOf(CommentEntity c, Anchor anchor) {
        if (anchor == null) {
            return "line:0";
        }
        if (anchor.endLine() != null) {
            return "line:" + anchor.line() + "-" + anchor.endLine();
        }
        return anchor.scenario() != null ? "scenario:" + anchor.scenario() : "line:" + anchor.line();
    }

    private Anchor resolveAnchor(String target, String markdown) {
        List<String> lines = markdown.lines().toList();
        Matcher lineMatcher = LINE_TARGET.matcher(target);
        if (lineMatcher.matches()) {
            int start = Integer.parseInt(lineMatcher.group(1));
            int end = lineMatcher.group(2) != null ? Integer.parseInt(lineMatcher.group(2)) : start;
            if (end < start) {
                int tmp = start;
                start = end;
                end = tmp;
            }
            String text = start >= 1 && start <= lines.size() ? lines.get(start - 1) : "";
            String scenario = scenarioAt(lines, start);
            return Anchor.forRange(start, end, text, "paragraph", scenario);
        }
        Matcher scenarioMatcher = SCENARIO_TARGET.matcher(target);
        if (scenarioMatcher.matches()) {
            String name = scenarioMatcher.group(1);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("Scenario: " + name)) {
                    return Anchor.forLine(i + 1, lines.get(i), "heading", name);
                }
            }
            return new Anchor(0, Anchor.hash(""), "heading", name, null);
        }
        return null;
    }

    private static String scenarioAt(List<String> lines, int lineNo) {
        String current = null;
        for (int i = 0; i < Math.min(lineNo, lines.size()); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("Scenario:")) {
                current = line.trim().substring("Scenario:".length()).trim();
            }
        }
        return current;
    }
}
