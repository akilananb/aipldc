package ai.pdlc.core.workflow;

import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** In-process fake of the board/repo/DB write activities for {@link FeatureWorkflowImplTest}. */
class FakeBoardSideEffects implements BoardSideEffects {

    final List<WorkItemRef> readyForStoryCalls = new CopyOnWriteArrayList<>();
    final List<WorkItemRef> grillQuestionsPosted = new CopyOnWriteArrayList<>();
    final List<StoryDraft> published = new CopyOnWriteArrayList<>();
    final List<Boolean> queuedFlags = new CopyOnWriteArrayList<>();
    final List<StoryDraft> revisions = new CopyOnWriteArrayList<>();
    final List<Integer> approvedVersions = new CopyOnWriteArrayList<>();
    final List<WorkItemRef> activatedStories = new CopyOnWriteArrayList<>();
    /** (boardId, version, verdict) tuples captured by {@link #saveQualityReport}. */
    final List<String> qualityReportsSaved = new CopyOnWriteArrayList<>();
    final AtomicInteger staleEscalations = new AtomicInteger();

    GateConfig gate1 = new GateConfig(List.of("PO", "SquadLead"), true);

    @Override
    public GateConfig loadGate1Config(String profile) {
        return gate1;
    }

    @Override
    public void postGrillQuestions(WorkItemRef item, GrillHandoff grill) {
        grillQuestionsPosted.add(item);
    }

    @Override
    public void transitionReadyForStory(WorkItemRef item, GrillHandoff grill) {
        readyForStoryCalls.add(item);
    }

    @Override
    public PublishResult publishStory(WorkItemRef feature, StoryDraft draft, int storyIndex, boolean queued) {
        published.add(draft);
        queuedFlags.add(queued);
        return new PublishResult("story-" + storyIndex, 1, "hash-v1");
    }

    @Override
    public PublishResult publishRevision(WorkItemRef story, int newVersion, StoryDraft draft, List<String> resolvedCommentIds) {
        revisions.add(draft);
        return new PublishResult(story.boardId(), newVersion, "hash-v" + newVersion);
    }

    @Override
    public void transitionApproved(WorkItemRef story, int version, int gate) {
        approvedVersions.add(version);
    }

    @Override
    public void escalateStale(WorkItemRef item) {
        staleEscalations.incrementAndGet();
    }

    @Override
    public void activateStory(WorkItemRef story) {
        activatedStories.add(story);
    }

    @Override
    public void saveQualityReport(WorkItemRef item, int version, QualityReport report) {
        qualityReportsSaved.add(item.boardId() + ":" + version + ":" + (report.passed() ? "passed" : "failed"));
    }

    GateConfig gate2 = new GateConfig(List.of("FSDeveloper", "QA"), true);
    String defaultBranch = "main";
    final List<PlanHandoff> tasksPublished = new CopyOnWriteArrayList<>();
    final List<WorkItemRef> inProgressCalls = new CopyOnWriteArrayList<>();
    final AtomicInteger prsOpened = new AtomicInteger();

    @Override
    public GateConfig loadGate2Config(String profile) {
        return gate2;
    }

    @Override
    public PublishTasksResult publishTasks(WorkItemRef story, PlanHandoff plan) {
        tasksPublished.add(plan);
        Map<String, String> taskBoardIds = new LinkedHashMap<>();
        for (Task t : plan.tasks()) {
            taskBoardIds.put(t.id(), "task-" + t.id());
        }
        return new PublishTasksResult(defaultBranch, taskBoardIds);
    }

    @Override
    public void transitionInProgress(WorkItemRef story) {
        inProgressCalls.add(story);
    }

    @Override
    public PRRef openStoryPr(WorkItemRef story, String branch, List<Task> tasks, List<BuildResult> results, ReviewHandoff review) {
        prsOpened.incrementAndGet();
        return new PRRef("1", "local://prs/1");
    }

    GateConfig gate3 = new GateConfig(List.of("PO", "SquadLead", "QA"), true);
    final List<ReleaseHandoff> releasePacksPublished = new CopyOnWriteArrayList<>();
    final List<ReleaseHandoff> deployedReleases = new CopyOnWriteArrayList<>();
    final List<MonitorHandoff> monitorEvaluations = new CopyOnWriteArrayList<>();
    boolean deployFails = false;

    @Override
    public GateConfig loadGate3Config(String profile) {
        return gate3;
    }

    @Override
    public void publishReleasePack(WorkItemRef story, ReleaseHandoff release) {
        releasePacksPublished.add(release);
    }

    @Override
    public void deployRelease(WorkItemRef story, ReleaseHandoff release, String branch) {
        if (deployFails) {
            throw new RuntimeException("deploy failed");
        }
        deployedReleases.add(release);
    }

    @Override
    public void fileMonitorCards(WorkItemRef story, MonitorHandoff monitor) {
        monitorEvaluations.add(monitor);
    }

    @Override
    public void saveAgentMentionResult(String commentId, String markdown, String status) {
        // Not exercised by FeatureWorkflowImplTest; the mention flow has its own workflow.
    }
}
