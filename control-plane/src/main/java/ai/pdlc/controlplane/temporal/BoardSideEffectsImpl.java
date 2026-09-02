package ai.pdlc.controlplane.temporal;

import ai.pdlc.controlplane.persistence.ArtifactEntity;
import ai.pdlc.controlplane.persistence.ArtifactRepository;
import ai.pdlc.controlplane.persistence.CommentEntity;
import ai.pdlc.controlplane.persistence.CommentRepository;
import ai.pdlc.controlplane.persistence.PrEntity;
import ai.pdlc.controlplane.persistence.PrRepository;
import ai.pdlc.controlplane.persistence.ReleaseDocumentEntity;
import ai.pdlc.controlplane.persistence.ReleaseDocumentRepository;
import ai.pdlc.controlplane.persistence.QualityReportEntity;
import ai.pdlc.controlplane.persistence.QualityReportRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.review.ReviewTrailService;
import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.domain.Anchor;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.ReleaseDocument;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewFinding;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.RunRef;
import ai.pdlc.core.domain.RunStatus;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.CiPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.review.GrillMdSerializer;
import ai.pdlc.core.review.ReleaseManifestSerializer;
import ai.pdlc.core.review.ReviewMdWriter;
import ai.pdlc.core.workflow.BoardSideEffects;
import ai.pdlc.core.workflow.BuildResult;
import ai.pdlc.core.workflow.PublishResult;
import ai.pdlc.core.workflow.PublishTasksResult;
import ai.pdlc.core.workflow.StoryDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link BoardSideEffects} activity implementation, hosted by control-plane's own Temporal worker
 * (task queue {@code reasoning}) so every board/repo/DB write uses control-plane's adapters and
 * datasource (orchestration-decision §6).
 */
@Component
public class BoardSideEffectsImpl implements BoardSideEffects {

    private static final Logger log = LoggerFactory.getLogger(BoardSideEffectsImpl.class);

    private static final String MAKER_BOT_IDENTITY = "po-agent-bot";
    private static final String GRILL_BOT_IDENTITY = "grill-agent-bot";
    private static final String RELEASE_BOT_IDENTITY = "release-agent-bot";

    private final PdlcConfig pdlcConfig;
    private final BoardPort board;
    private final RepoPort repo;
    private final CiPort ci;
    private final WorkItemRepository workItems;
    private final ArtifactRepository artifacts;
    private final CommentRepository comments;
    private final ReviewTrailService reviewTrail;
    private final PrRepository prs;
    private final ReleaseDocumentRepository releaseDocuments;
    private final QualityReportRepository qualityReports;
    private final JdbcTemplate jdbc;

    public BoardSideEffectsImpl(PdlcConfig pdlcConfig, BoardPort board, RepoPort repo, CiPort ci,
                                 WorkItemRepository workItems, ArtifactRepository artifacts,
                                 CommentRepository comments, ReviewTrailService reviewTrail,
                                 PrRepository prs, ReleaseDocumentRepository releaseDocuments,
                                 QualityReportRepository qualityReports, JdbcTemplate jdbc) {
        this.pdlcConfig = pdlcConfig;
        this.board = board;
        this.repo = repo;
        this.ci = ci;
        this.workItems = workItems;
        this.artifacts = artifacts;
        this.comments = comments;
        this.reviewTrail = reviewTrail;
        this.prs = prs;
        this.releaseDocuments = releaseDocuments;
        this.qualityReports = qualityReports;
        this.jdbc = jdbc;
    }

    @Override
    public GateConfig loadGate1Config(String profile) {
        return pdlcConfig.profile(profile).gate("G1");
    }

    @Override
    public void postGrillQuestions(WorkItemRef item, GrillHandoff grill) {
        ensureWorkItem(item, "feature", null);
        board.addComment(item, formatGrillQuestions(grill), GRILL_BOT_IDENTITY);
    }

    @Override
    public void transitionReadyForStory(WorkItemRef item, GrillHandoff grill) {
        WorkItemEntity feature = ensureWorkItem(item, "feature", null);
        board.transition(item, CanonicalState.READY_FOR_STORY);
        board.attach(item, "grill.md", GrillMdSerializer.render(grill));
        workItems.save(feature.withCanonicalState(CanonicalState.READY_FOR_STORY.wireValue()));
    }

    @Override
    public PublishResult publishStory(WorkItemRef feature, StoryDraft draft, int storyIndex, boolean queued) {
        ensureWorkItem(feature, "feature", null);

        String idempotencyKey = feature.workflowId() + ":publishStory:" + storyIndex;
        String title = firstHeadingOrDefault(draft.storyMarkdown(), "Untitled story");
        var created = board.createItem(feature.profile(), "story", Map.of(
                "title", title,
                "description", draft.storyMarkdown(),
                "_idempotencyKey", idempotencyKey), feature.boardId());
        WorkItemRef storyRef = new WorkItemRef(feature.profile(), created.id());
        CanonicalState initialState = queued ? CanonicalState.QUEUED : CanonicalState.AWAITING_G1;
        board.transition(storyRef, initialState);

        String slug = draft.handoff().change();
        Map<String, String> files = changeFolderFiles(slug, draft);
        String defaultBranch = pdlcConfig.profile(feature.profile()).repo().defaultBranch();
        var commit = repo.writeFiles(defaultBranch, files, "story drafted: " + title, MAKER_BOT_IDENTITY);

        String contentHash = Anchor.hash(draft.storyMarkdown());
        WorkItemEntity storyRow = ensureWorkItem(storyRef, "story", feature.boardId());
        workItems.save(storyRow.withCanonicalState(initialState.wireValue()).withSpecChangePath(slug));
        artifacts.save(ArtifactEntity.newRow(storyRow.id(), "story", 1, contentHash, commit.sha(), MAKER_BOT_IDENTITY));

        String investSummary = draft.handoff().investAllPass() ? "INVEST pass" : "INVEST partial";
        String dorUnmetSummary = draft.handoff().dorUnmet().isEmpty() ? "none" : String.join(", ", draft.handoff().dorUnmet());
        reviewTrail.appendReviewMd(storyRef, slug, ReviewMdWriter.draftBlock(1, OffsetDateTime.now(), investSummary, dorUnmetSummary));
        reviewTrail.appendReviewEvent(storyRow.id(), "drafted", Map.of("version", 1, "invest", investSummary, "dorUnmet", dorUnmetSummary));

        return new PublishResult(created.id(), 1, contentHash);
    }

    @Override
    public PublishResult publishRevision(WorkItemRef story, int newVersion, StoryDraft draft, List<String> resolvedCommentIds) {
        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(story.profile(), story.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for story " + story));

        String slug = draft.handoff().change();
        Map<String, String> files = changeFolderFiles(slug, draft);
        String defaultBranch = pdlcConfig.profile(story.profile()).repo().defaultBranch();
        var commit = repo.writeFiles(defaultBranch, files, "story revised (v" + newVersion + ")", MAKER_BOT_IDENTITY);

        String contentHash = Anchor.hash(draft.storyMarkdown());
        artifacts.save(ArtifactEntity.newRow(storyRow.id(), "story", newVersion, contentHash, commit.sha(), MAKER_BOT_IDENTITY));
        board.updateFields(story, Map.of("description", draft.storyMarkdown()));

        List<String> resolvedRefs = resolvedCommentIds.stream().map(id -> {
            try {
                CommentEntity entity = comments.findById(UUID.fromString(id)).orElse(null);
                if (entity != null) {
                    comments.save(entity.resolvedIn(newVersion, "resolved in v" + newVersion));
                    return "comment@" + id.substring(0, Math.min(8, id.length())) + " resolved";
                }
            } catch (IllegalArgumentException ignored) {
                // comment id not a persisted UUID (e.g. board comment) - still note it as resolved.
            }
            return "comment@" + id + " resolved";
        }).toList();

        String investSummary = draft.handoff().investAllPass() ? "pass" : "partial";
        String dorSummary = draft.handoff().dorUnmet().isEmpty() ? "pass" : "partial";
        reviewTrail.appendReviewMd(story, slug, ReviewMdWriter.revisionBlock(newVersion, OffsetDateTime.now(),
                List.of("story revised to address " + resolvedCommentIds.size() + " comment(s)"), investSummary, dorSummary, resolvedRefs));
        reviewTrail.appendReviewEvent(storyRow.id(), "revision", Map.of("version", newVersion, "resolved", resolvedCommentIds));

        return new PublishResult(story.boardId(), newVersion, contentHash);
    }

    @Override
    public void transitionApproved(WorkItemRef story, int version, int gate) {
        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(story.profile(), story.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for story " + story));
        board.transition(story, CanonicalState.APPROVED);
        workItems.save(storyRow.withCanonicalState(CanonicalState.APPROVED.wireValue()));

        reviewTrail.appendReviewMd(story, storyRow.specChangePath(), ReviewMdWriter.gatePassedBlock(gate, version));
        reviewTrail.appendReviewEvent(storyRow.id(), "gate-passed", Map.of("version", version, "gate", gate));
    }

    @Override
    public void escalateStale(WorkItemRef item) {
        WorkItemEntity feature = ensureWorkItem(item, "feature", null);
        board.addComment(item, "@SquadLead — 5 working days with open grill questions; please answer or park.", GRILL_BOT_IDENTITY);
        board.transition(item, CanonicalState.STALE);
        workItems.save(feature.withCanonicalState(CanonicalState.STALE.wireValue()));
        reviewTrail.appendReviewEvent(feature.id(), "stale-escalation", Map.of("boardId", item.boardId()));
    }

    @Override
    public GateConfig loadGate2Config(String profile) {
        return pdlcConfig.profile(profile).gate("G2");
    }

    @Override
    public PublishTasksResult publishTasks(WorkItemRef story, PlanHandoff plan) {
        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(story.profile(), story.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for story " + story));

        Map<String, String> taskBoardIds = new LinkedHashMap<>();
        for (Task task : plan.tasks()) {
            var created = board.createItem(story.profile(), "task", Map.of(
                    "title", task.title(),
                    "description", taskDescription(task),
                    "_idempotencyKey", story.workflowId() + ":task:" + task.id()), story.boardId());
            ensureWorkItem(new WorkItemRef(story.profile(), created.id()), "task", story.boardId());
            taskBoardIds.put(task.id(), created.id());
        }

        String defaultBranch = pdlcConfig.profile(story.profile()).repo().defaultBranch();
        if (storyRow.specChangePath() != null) {
            repo.writeFiles(defaultBranch, Map.of(storyRow.specChangePath() + "/tasks.md", tasksMd(plan)),
                    "tasks planned", "plan-agent-bot");
        }

        board.transition(story, CanonicalState.PLANNED);
        workItems.save(storyRow.withCanonicalState(CanonicalState.PLANNED.wireValue()));
        reviewTrail.appendReviewEvent(storyRow.id(), "planned",
                Map.of("tasks", plan.tasks().size(), "waves", plan.waves().size()));

        return new PublishTasksResult(defaultBranch, taskBoardIds);
    }

    @Override
    public void transitionInProgress(WorkItemRef story) {
        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(story.profile(), story.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for story " + story));
        board.transition(story, CanonicalState.IN_PROGRESS);
        workItems.save(storyRow.withCanonicalState(CanonicalState.IN_PROGRESS.wireValue()));
    }

    @Override
    public PRRef openStoryPr(WorkItemRef story, String branch, List<Task> tasks, List<BuildResult> results, ReviewHandoff review) {
        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(story.profile(), story.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for story " + story));
        String defaultBranch = pdlcConfig.profile(story.profile()).repo().defaultBranch();
        String storyTitle = board.getItem(story).title();

        PRRef pr = repo.openPR(branch, defaultBranch, "Build: " + storyTitle, prBody(review));
        prs.save(PrEntity.newRow(storyRow.id(), pr.id(), branch, defaultBranch));

        for (BuildResult r : results) {
            jdbc.update("""
                    INSERT INTO runs (work_item_id, agent, workflow_run_id, trace_url, tokens, iterations, outcome)
                    VALUES (?, 'build-worker', ?, NULL, ?, ?, ?)
                    """, storyRow.id(), story.workflowId(), r.tokens(), r.iterations(), r.verifier().result());
        }

        for (ReviewFinding f : review.findings()) {
            repo.commentOnPR(pr.id(), "[" + f.severity().wireValue() + "/" + f.category() + "] " + f.message(), null);
        }

        if (storyRow.specChangePath() != null) {
            List<String> taskLines = results.stream().map(r -> {
                Task task = tasks.stream().filter(t -> t.id().equals(r.taskId())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No such task: " + r.taskId()));
                return task.id() + " " + task.scenario() + ": " + r.verifier().result()
                        + " (iterations=" + r.iterations() + (r.escalation() != null ? ", escalation: " + r.escalation() : "") + ")";
            }).toList();
            List<String> findingLines = review.findings().stream()
                    .map(f -> "[" + f.severity().wireValue() + "] " + f.category() + ": " + f.message()).toList();
            reviewTrail.appendReviewMd(story, storyRow.specChangePath(),
                    ReviewMdWriter.prOpenedBlock(tasks.size(), branch, defaultBranch, pr.url(), taskLines, findingLines));
        }
        reviewTrail.appendReviewEvent(storyRow.id(), "pr-opened",
                Map.of("branch", branch, "prId", pr.id(), "findings", review.findings().size()));

        board.transition(story, CanonicalState.AWAITING_G2);
        workItems.save(storyRow.withCanonicalState(CanonicalState.AWAITING_G2.wireValue()));

        return pr;
    }

    @Override
    public void recordTaskResults(WorkItemRef story, Map<String, String> taskBoardIds, List<BuildResult> results) {
        for (BuildResult r : results) {
            String taskBoardId = taskBoardIds.get(r.taskId());
            if (taskBoardId == null) {
                continue;
            }
            WorkItemRef taskRef = new WorkItemRef(story.profile(), taskBoardId);
            WorkItemEntity taskRow = workItems.findByProfileAndBoardId(taskRef.profile(), taskRef.boardId()).orElse(null);
            if (taskRow == null) {
                continue;
            }

            board.transition(taskRef, CanonicalState.DONE);
            workItems.save(taskRow.withCanonicalState(CanonicalState.DONE.wireValue()));

            boolean passed = "green".equals(r.verifier().result());
            List<String> findings = new ArrayList<>();
            if (!passed) {
                findings.add("verifier: " + r.verifier().notes());
            }
            if (r.escalation() != null) {
                findings.add("escalation: " + r.escalation());
            }
            String reportMd = "VERDICT: " + (passed ? "PASS" : "FAIL")
                    + "\nverifier: " + r.verifier().result() + " (" + r.verifier().notes() + ")"
                    + "\ncommit: " + r.commitSha() + "\niterations: " + r.iterations()
                    + (r.escalation() != null ? "\nescalation: " + r.escalation() : "");
            saveQualityReport(taskRef, 2, new QualityReport("task", passed, passed ? 100 : 0, findings, reportMd));
        }
    }

    @Override
    public GateConfig loadGate3Config(String profile) {
        return pdlcConfig.profile(profile).gate("G3");
    }

    @Override
    public void publishReleasePack(WorkItemRef story, ReleaseHandoff release) {
        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(story.profile(), story.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for story " + story));
        String defaultBranch = pdlcConfig.profile(story.profile()).repo().defaultBranch();
        String storyTitle = board.getItem(story).title();

        String idempotencyKey = story.workflowId() + ":release:" + release.releaseId();
        var created = board.createItem(story.profile(), "release", Map.of(
                "title", "Release: " + storyTitle,
                "description", "Release pack " + release.releaseId(),
                "_idempotencyKey", idempotencyKey), story.boardId());
        ensureWorkItem(new WorkItemRef(story.profile(), created.id()), "release", story.boardId());

        Map<String, String> files = new LinkedHashMap<>();
        for (ReleaseDocument doc : release.documents()) {
            files.put("release/" + release.releaseId() + "/" + doc.id() + ".md", doc.content());
        }
        files.put("release/" + release.releaseId() + "/manifest.yaml", ReleaseManifestSerializer.render(release));
        repo.writeFiles(defaultBranch, files, "release pack drafted: " + release.releaseId(), RELEASE_BOT_IDENTITY);

        int packVersion = 1; // gate 3 always starts its episode at version 1 (FeatureWorkflowImpl#run step 10-11)
        List<String> documentLines = new ArrayList<>();
        for (ReleaseDocument doc : release.documents()) {
            String contentHash = Anchor.hash(doc.content());
            releaseDocuments.save(ReleaseDocumentEntity.newRow(storyRow.id(), release.releaseId(), doc.id(),
                    doc.title(), doc.content(), doc.checkerRole(), contentHash, packVersion));
            documentLines.add(doc.id() + " (" + doc.title() + ", checker: " + doc.checkerRole() + ")");
        }

        board.transition(story, CanonicalState.AWAITING_G3);
        workItems.save(storyRow.withCanonicalState(CanonicalState.AWAITING_G3.wireValue()));

        if (storyRow.specChangePath() != null) {
            reviewTrail.appendReviewMd(story, storyRow.specChangePath(),
                    ReviewMdWriter.releasePackBlock(release.releaseId(), documentLines));
        }
        reviewTrail.appendReviewEvent(storyRow.id(), "release-pack-published",
                Map.of("releaseId", release.releaseId(), "documents", release.documents().size()));
    }

    @Override
    public void deployRelease(WorkItemRef story, ReleaseHandoff release, String branch) {
        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(story.profile(), story.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for story " + story));

        RunRef run = ci.runDeploy("production", release.releaseId(), Map.of("branch", branch));
        RunStatus status = ci.getRun(run.id());
        jdbc.update("""
                INSERT INTO runs (work_item_id, agent, workflow_run_id, trace_url, tokens, iterations, outcome)
                VALUES (?, 'ci-deploy', ?, NULL, NULL, NULL, ?)
                """, storyRow.id(), story.workflowId(), status.state());
        if (!status.success()) {
            throw new IllegalStateException("Deploy failed for release " + release.releaseId() + ": run " + run.id());
        }

        board.transition(story, CanonicalState.DONE);
        workItems.save(storyRow.withCanonicalState(CanonicalState.DONE.wireValue()));

        if (storyRow.specChangePath() != null) {
            reviewTrail.appendReviewMd(story, storyRow.specChangePath(),
                    ReviewMdWriter.deployedBlock("production", release.releaseId(), branch, run.id()));
        }
        reviewTrail.appendReviewEvent(storyRow.id(), "deployed",
                Map.of("releaseId", release.releaseId(), "runId", run.id(), "branch", branch));
    }

    @Override
    public void fileMonitorCards(WorkItemRef story, MonitorHandoff monitor) {
        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(story.profile(), story.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for story " + story));

        List<String> tripLines = new ArrayList<>();
        for (MonitorHandoff.Trip trip : monitor.trips()) {
            var created = board.createItem(story.profile(), trip.proposedType(), Map.of(
                    "title", "Monitor trip: " + trip.ruleId(),
                    "description", trip.evidence(),
                    "_idempotencyKey", story.workflowId() + ":monitor:" + trip.ruleId()), story.boardId());
            WorkItemRef tripRef = new WorkItemRef(story.profile(), created.id());
            ensureWorkItem(tripRef, trip.proposedType(), story.boardId());
            board.updateFields(tripRef, Map.of("owner", trip.owner()));
            tripLines.add(trip.ruleId() + " -> " + trip.proposedType() + " (owner: " + trip.owner() + "): " + trip.evidence());
        }

        if (storyRow.specChangePath() != null) {
            reviewTrail.appendReviewMd(story, storyRow.specChangePath(),
                    ReviewMdWriter.monitorEvaluationBlock(tripLines));
        }
        reviewTrail.appendReviewEvent(storyRow.id(), "monitor-evaluated", Map.of("trips", monitor.trips().size()));
    }

    // -- helpers -------------------------------------------------------------------------------

    private WorkItemEntity ensureWorkItem(WorkItemRef ref, String kind, String parentId) {
        return workItems.findByProfileAndBoardId(ref.profile(), ref.boardId())
                .orElseGet(() -> {
                    String provider = pdlcConfig.profile(ref.profile()).board().provider();
                    return workItems.save(WorkItemEntity.newRow(ref.profile(), provider, ref.boardId(), kind, parentId, CanonicalState.NEW.wireValue(), null));
                });
    }

    private static Map<String, String> changeFolderFiles(String slug, StoryDraft draft) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(slug + "/proposal.md", draft.storyMarkdown());
        draft.specDeltaFiles().forEach((path, content) -> files.put(slug + "/" + path, content));
        return files;
    }

    /** One numbered question per line, category prefixed - playbook §1 "Does" step 3. */
    private static String formatGrillQuestions(GrillHandoff grill) {
        StringBuilder sb = new StringBuilder();
        List<GrillQuestion> questions = grill.questions();
        for (int i = 0; i < questions.size(); i++) {
            GrillQuestion q = questions.get(i);
            sb.append(i + 1).append(". [").append(q.category().wireValue()).append("] ").append(q.question());
            if (q.evidence() != null && !q.evidence().isBlank()) {
                sb.append(" (evidence: ").append(q.evidence()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*\\S)\\s*$");

    private static String firstHeadingOrDefault(String markdown, String fallback) {
        for (String line : markdown.lines().toList()) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                return m.group(1);
            }
        }
        return fallback;
    }

    private static String taskDescription(Task task) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scenario: ").append(task.scenario()).append('\n');
        sb.append("Area: ").append(task.area()).append('\n');
        sb.append("Touches: ").append(String.join(", ", task.touches())).append('\n');
        sb.append("Test: ").append(task.testPath()).append('\n');
        if (!task.blockedBy().isEmpty()) {
            sb.append("Blocked by: ").append(String.join(", ", task.blockedBy())).append('\n');
        }
        return sb.toString();
    }

    private static String tasksMd(PlanHandoff plan) {
        StringBuilder sb = new StringBuilder("# tasks.md\n");
        for (int i = 0; i < plan.waves().size(); i++) {
            sb.append("\n## Wave ").append(i + 1).append('\n');
            for (String taskId : plan.waves().get(i)) {
                Task task = plan.task(taskId);
                sb.append("- ").append(task.id()).append(' ').append(task.title())
                        .append(" (proves: ").append(task.scenario())
                        .append("; touches: ").append(String.join(", ", task.touches()))
                        .append("; test: ").append(task.testPath()).append(")\n");
            }
        }
        return sb.toString();
    }

    private static String prBody(ReviewHandoff review) {
        StringBuilder sb = new StringBuilder("## Traceability\n");
        for (ReviewHandoff.TraceabilityRow row : review.traceability()) {
            sb.append("- ").append(row.scenario()).append(" -> ").append(row.testRef())
                    .append(" -> ").append(row.codeRef()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void saveAgentMentionResult(String commentId, String markdown, String status) {
        CommentEntity entity = comments.findById(UUID.fromString(commentId)).orElse(null);
        if (entity == null) {
            log.warn("Agent mention result for comment {} dropped: comment row no longer exists", commentId);
            return;
        }
        comments.save(entity.withAgentResult(markdown, status));

        ArtifactEntity artifact = artifacts.findById(entity.artifactId()).orElse(null);
        if (artifact == null) {
            log.warn("Agent mention result for comment {} saved, but its artifact {} no longer exists; skipping review_events", commentId, entity.artifactId());
            return;
        }
        reviewTrail.appendReviewEvent(artifact.workItemId(), "agent-result-drafted",
                Map.of("commentId", commentId, "agent", entity.agentName(), "status", status));
    }

    @Override
    public void activateStory(WorkItemRef story) {
        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(story.profile(), story.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for story " + story));
        board.transition(story, CanonicalState.AWAITING_G1);
        workItems.save(storyRow.withCanonicalState(CanonicalState.AWAITING_G1.wireValue()));
        reviewTrail.appendReviewEvent(storyRow.id(), "story-activated", Map.of("boardId", story.boardId()));
    }

    @Override
    public void saveQualityReport(WorkItemRef item, int version, QualityReport report) {
        WorkItemEntity row = workItems.findByProfileAndBoardId(item.profile(), item.boardId())
                .orElseThrow(() -> new IllegalStateException("No work_items row for " + item));
        String verdict = report.passed() ? "passed" : "failed";
        qualityReports.save(QualityReportEntity.newRow(row.id(), version, report.subjectKind(), verdict, report.score(), report.reportMd()));
        reviewTrail.appendReviewEvent(row.id(), "quality-evaluated",
                Map.of("version", version, "verdict", verdict, "score", report.score()));
        if (row.specChangePath() != null) {
            reviewTrail.appendReviewMd(item, row.specChangePath(),
                    ReviewMdWriter.qualityBlock(version, verdict, report.score(), OffsetDateTime.now()));
        }
    }
}
