package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.demo.DemoSnapshotRepository;
import ai.pdlc.controlplane.demo.DemoSnapshotService;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.persistence.ApprovalEntity;
import ai.pdlc.controlplane.persistence.ApprovalRepository;
import ai.pdlc.controlplane.persistence.ArtifactEntity;
import ai.pdlc.controlplane.persistence.ArtifactRepository;
import ai.pdlc.controlplane.persistence.CommentEntity;
import ai.pdlc.controlplane.persistence.CommentRepository;
import ai.pdlc.controlplane.persistence.IngestedEventStore;
import ai.pdlc.controlplane.persistence.PrRepository;
import ai.pdlc.controlplane.persistence.QualityReportEntity;
import ai.pdlc.controlplane.persistence.QualityReportRepository;
import ai.pdlc.controlplane.persistence.ReleaseDocumentRepository;
import ai.pdlc.controlplane.persistence.ReviewEventEntity;
import ai.pdlc.controlplane.persistence.ReviewEventRepository;
import ai.pdlc.controlplane.persistence.RunRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.review.CommentReanchorer;
import ai.pdlc.controlplane.review.ReviewTrailService;
import ai.pdlc.controlplane.temporal.FeatureWorkflowStarter;
import ai.pdlc.controlplane.temporal.WorkflowStubs;
import ai.pdlc.controlplane.web.dto.ApproveRequest;
import ai.pdlc.controlplane.web.dto.CommentRequest;
import ai.pdlc.controlplane.web.dto.GrillAnswerRequest;
import ai.pdlc.controlplane.web.dto.ItemSummaryDto;
import ai.pdlc.controlplane.web.dto.QualityReportDto;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.workflow.FeatureWorkflow;
import io.temporal.client.WorkflowClient;
import jakarta.servlet.http.HttpServletRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the snapshot read-only guard ({@link DemoSnapshotService#requireWritable}) is the FIRST
 * thing every mutation route does: a seeded {@code demo_snapshots} row is rejected with HTTP 409
 * semantics ({@link ConflictException}, message "read-only") before the board, git, webhook ledger,
 * approval/comment/review-event tables, or a Temporal workflow stub can be touched - while an
 * ordinary non-snapshot item passes the guard and reaches its normal (Temporal) path.
 *
 * <p>Follows {@code PersistenceIntegrationTest}'s pattern (real {@code postgres:16-alpine} +
 * explicit Flyway migrate + minimal {@code @SpringBootTest} context) but constructs the five
 * controllers directly, wiring the only two moving parts - the {@code WorkflowStubs} (Temporal
 * client stub factory) and the {@code BoardPort}/{@code RepoPort} adapters - as mocks. The
 * snapshot controller set uses a {@code WorkflowStubs} whose {@code featureWorkflow} throws
 * {@code AssertionError}, so any snapshot read/mutation that reaches Temporal fails loudly rather
 * than silently returning a stub.
 */
@Testcontainers
@SpringBootTest(classes = DemoReadOnlyTest.TestApp.class)
class DemoReadOnlyTest {

    @Configuration
    @EnableAutoConfiguration
    @EnableJdbcRepositories(basePackages = {"ai.pdlc.controlplane.persistence", "ai.pdlc.controlplane.demo"})
    static class TestApp {
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Autowired
    WorkItemRepository workItems;
    @Autowired
    ArtifactRepository artifacts;
    @Autowired
    CommentRepository comments;
    @Autowired
    ApprovalRepository approvals;
    @Autowired
    ReviewEventRepository reviewEvents;
    @Autowired
    QualityReportRepository qualityReports;
    @Autowired
    RunRepository runs;
    @Autowired
    PrRepository prs;
    @Autowired
    ReleaseDocumentRepository releaseDocuments;
    @Autowired
    DemoSnapshotRepository snapshots;
    @Autowired
    JdbcTemplate jdbc;

    // Seeded ids.
    private UUID snapFeatureId;
    private UUID snapStoryId;
    private UUID liveFeatureId;
    private UUID liveStoryId;
    private UUID snapArtifactId;
    private UUID snapCommentId;

    // Mocks rebuilt per test so Mockito verify() never accumulates across tests.
    private BoardPort snapshotBoard;
    private BoardPort liveBoard;
    private WorkflowStubs snapshotStubs;
    private WorkflowStubs liveStubs;
    private RepoPort repo;
    private IdentityResolver identityResolver;
    private PdlcConfig pdlcConfig;
    private WorkflowClient workflowClient;
    private FeatureWorkflowStarter featureWorkflowStarter;
    private HttpServletRequest httpRequest;

    private DemoSnapshotService demoSnapshots;
    private Controllers snapshotControllers;
    private Controllers liveControllers;

    private record Controllers(ItemsController items, ArtifactsController artifacts, PrController pr,
                               ReleaseController release, WebhookController webhook) {
    }

    private record StateSnapshot(List<WorkItemEntity> workItems, List<ArtifactEntity> artifacts,
                                 List<CommentEntity> comments, List<ApprovalEntity> approvals,
                                 List<ReviewEventEntity> reviewEvents) {
    }

    @BeforeEach
    void setUp() {
        // Full reset in FK-safe order, then seed the snapshot + live catalog rows.
        jdbc.update("DELETE FROM demo_snapshots"); // DemoSnapshotRepository is intentionally read-only
        comments.deleteAll();
        approvals.deleteAll();
        artifacts.deleteAll();
        reviewEvents.deleteAll();
        runs.deleteAll();
        prs.deleteAll();
        releaseDocuments.deleteAll();
        qualityReports.deleteAll();
        jdbc.update("DELETE FROM ingested_events");
        workItems.deleteAll();
        seed();
        buildHarness();
    }

    private void seed() {
        WorkItemEntity snapFeature = workItems.save(WorkItemEntity.newRow(
                "local", "in-memory", "demo-feature", "feature", null, "needs-clarification", null));
        WorkItemEntity snapStory = workItems.save(WorkItemEntity.newRow(
                "local", "in-memory", "demo-story", "story", "demo-feature", "awaiting-G1",
                "openspec/changes/preserve-customizations"));
        WorkItemEntity liveFeature = workItems.save(WorkItemEntity.newRow(
                "local", "in-memory", "live-feature", "feature", null, "new", null));
        WorkItemEntity liveStory = workItems.save(WorkItemEntity.newRow(
                "local", "in-memory", "live-story", "story", "live-feature", "new", null));

        snapFeatureId = snapFeature.id();
        snapStoryId = snapStory.id();
        liveFeatureId = liveFeature.id();
        liveStoryId = liveStory.id();

        // demo_snapshots.work_item_id is a non-generated primary key (it IS the work item id), so
        // Spring Data JDBC's save() would treat it as an existing row and issue an UPDATE instead of
        // an INSERT. The production initializer imports this table directly for the same reason.
        jdbc.update("""
                INSERT INTO demo_snapshots
                    (work_item_id, seed_version, snapshot_key, label, ordinal, source_ref, replay, git_ref, gate_json, grill_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapFeatureId, "restaurant-v1", "demo-06a-feature", "Intake and clarification",
                1, "06-agent-intake@sha", false, null, null, null);
        jdbc.update("""
                INSERT INTO demo_snapshots
                    (work_item_id, seed_version, snapshot_key, label, ordinal, source_ref, replay, git_ref, gate_json, grill_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapStoryId, "restaurant-v1", "demo-06b-story", "First draft",
                2, "06-agent-intake@sha", false, "snapshot-sha", null, null);

        ArtifactEntity artifact = artifacts.save(ArtifactEntity.newRow(
                snapStoryId, "story", 1, "abc123", "snapshot-sha", "po-agent-bot"));
        snapArtifactId = artifact.id();

        CommentEntity comment = comments.save(CommentEntity.newRow(
                snapArtifactId, 1, "squad-lead@bistro", "SquadLead", "{\"line\":13}",
                "blocking comment", "change", true));
        snapCommentId = comment.id();

        approvals.save(ApprovalEntity.newRow(snapArtifactId, 1, "abc123", "po@bistro", "PO", "story", OffsetDateTime.now()));
        reviewEvents.save(ReviewEventEntity.newRow(snapStoryId, "drafted", "{}"));
        qualityReports.save(QualityReportEntity.newRow(snapStoryId, 1, "story", "failed", 52, "# quality report"));
    }

    private void buildHarness() {
        snapshotBoard = mock(BoardPort.class);
        liveBoard = mock(BoardPort.class);
        repo = mock(RepoPort.class);
        identityResolver = mock(IdentityResolver.class);
        pdlcConfig = mock(PdlcConfig.class);
        workflowClient = mock(WorkflowClient.class);
        featureWorkflowStarter = mock(FeatureWorkflowStarter.class);
        httpRequest = mock(HttpServletRequest.class);

        // Temporal must never be touched for a snapshot - any accidental reach throws AssertionError.
        snapshotStubs = mock(WorkflowStubs.class);
        when(snapshotStubs.featureWorkflow(any())).thenThrow(new AssertionError("Temporal must not be touched for a snapshot"));
        // The live item's normal path DOES reach Temporal; a "no running workflow" RuntimeException is
        // the real control-plane contract for a story with no FeatureWorkflow.
        FeatureWorkflow liveWorkflow = mock(FeatureWorkflow.class);
        when(liveWorkflow.grill()).thenThrow(new RuntimeException("no running workflow"));
        when(liveWorkflow.state()).thenThrow(new RuntimeException("no running workflow"));
        liveStubs = mock(WorkflowStubs.class);
        when(liveStubs.featureWorkflow(any())).thenReturn(liveWorkflow);

        BoardConfig boardConfig = new BoardConfig("in-memory", "local", "PDLC", Map.of(), Map.of(), null);
        RepoConfig repoConfig = new RepoConfig("local-git", "/tmp/x", "demo/restaurant-v1", "openspec/changes");
        Profile profile = new Profile("local", boardConfig, repoConfig, null, null, Map.of(
                "G1", new GateConfig(List.of("PO", "SquadLead"), true),
                "G2", new GateConfig(List.of("FSDeveloper", "QA"), true),
                "G3", new GateConfig(List.of("PO", "SquadLead"), true)));
        when(pdlcConfig.profile("local")).thenReturn(profile);
        when(identityResolver.resolve(any())).thenReturn(new Identity("po@bistro", "PO"));

        WorkItem boardItem = new WorkItem("demo-story", "story", "Snapshot story title", "Snapshot description",
                CanonicalState.AWAITING_G1, "demo-feature", null, List.of());
        when(snapshotBoard.getItem(any())).thenReturn(boardItem);
        when(snapshotBoard.listComments(any())).thenReturn(List.of());
        WorkItem liveItem = new WorkItem("live-story", "story", "Live story title", "Live description",
                CanonicalState.NEW, "live-feature", null, List.of());
        when(liveBoard.getItem(any())).thenReturn(liveItem);
        when(liveBoard.listComments(any())).thenReturn(List.of());
        when(liveBoard.onWebhook(anyString(), any())).thenReturn(
                new CanonicalEvent(new WorkItemRef("local", "live-feature"), CanonicalEvent.Kind.ITEM_UPDATED, 1L));

        when(repo.readFile(anyString(), anyString())).thenReturn("# curated snapshot review");

        demoSnapshots = new DemoSnapshotService(snapshots, workItems);
        snapshotControllers = buildControllers(snapshotStubs, snapshotBoard);
        liveControllers = buildControllers(liveStubs, liveBoard);
    }

    private Controllers buildControllers(WorkflowStubs stubs, BoardPort board) {
        ReviewTrailService reviewTrail = new ReviewTrailService(pdlcConfig, repo, reviewEvents);
        CommentReanchorer reanchorer = new CommentReanchorer();
        IngestedEventStore ingestedEvents = new IngestedEventStore(jdbc);

        ItemsController items = new ItemsController(workItems, artifacts, approvals, qualityReports, runs,
                board, pdlcConfig, stubs, identityResolver, reviewTrail, repo, demoSnapshots);
        ArtifactsController arts = new ArtifactsController(workItems, artifacts, comments, repo, reanchorer,
                reviewTrail, stubs, identityResolver, workflowClient, pdlcConfig, demoSnapshots);
        PrController pr = new PrController(workItems, prs, repo, pdlcConfig, stubs, identityResolver, demoSnapshots);
        ReleaseController release = new ReleaseController(workItems, releaseDocuments, stubs, identityResolver, demoSnapshots);
        WebhookController webhook = new WebhookController(board, ingestedEvents, featureWorkflowStarter,
                workflowClient, new Profile("local", new BoardConfig("in-memory", "local", "PDLC", Map.of(), Map.of(), null),
                new RepoConfig("local-git", "/tmp/x", "demo/restaurant-v1", "openspec/changes"), null, null, Map.of()),
                demoSnapshots);
        return new Controllers(items, arts, pr, release, webhook);
    }

    // ---------------------------------------------------------------------------------------------
    // Mutation routes: snapshot item -> ConflictException (409, "read-only"), zero side effects.
    // ---------------------------------------------------------------------------------------------

    @Test
    void itemsControllerMutationsRejectSnapshotWithConflictAndPreserveState() {
        assertRejectedAsSnapshot(() -> snapshotControllers.items().approve(snapStoryId, new ApproveRequest("note"), httpRequest));
        assertRejectedAsSnapshot(() -> snapshotControllers.items().requestChanges(snapStoryId, httpRequest));
        assertRejectedAsSnapshot(() -> snapshotControllers.items().answerGrillQuestion(snapStoryId, "q1", new GrillAnswerRequest("answer"), httpRequest));
        assertRejectedAsSnapshot(() -> snapshotControllers.items().parkGrillQuestion(snapStoryId, "q1", httpRequest));
    }

    @Test
    void artifactsControllerMutationsRejectSnapshotWithConflictAndPreserveState() {
        assertRejectedAsSnapshot(() -> snapshotControllers.artifacts().addComment(snapStoryId,
                new CommentRequest("line:1", "blocking comment", "change", true), httpRequest));
        assertRejectedAsSnapshot(() -> snapshotControllers.artifacts().approveAgentResult(snapStoryId, snapCommentId, httpRequest));
    }

    @Test
    void prAndReleaseMutationsRejectSnapshotWithConflictAndPreserveState() {
        assertRejectedAsSnapshot(() -> snapshotControllers.pr().approve(snapStoryId, new ApproveRequest(null), httpRequest));
        assertRejectedAsSnapshot(() -> snapshotControllers.pr().requestChanges(snapStoryId, httpRequest));
        assertRejectedAsSnapshot(() -> snapshotControllers.release().sign(snapStoryId, "doc-1", new ApproveRequest(null), httpRequest));
        assertRejectedAsSnapshot(() -> snapshotControllers.release().requestChanges(snapStoryId, httpRequest));
    }

    @Test
    void webhookIngressRejectsSnapshotWithConflictBeforeBoardOrLedger() {
        Map<String, Object> itemUpdated = Map.of("boardId", "demo-story", "kind", "item.updated", "rev", 7);
        Map<String, Object> commentAdded = Map.of("boardId", "demo-story", "kind", "comment.added", "rev", 8,
                "author", "po@bistro", "text", "replayed comment");

        for (Map<String, Object> payload : List.of(itemUpdated, commentAdded)) {
            assertThatThrownBy(() -> snapshotControllers.webhook().local(payload))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("read-only");
            assertThatThrownBy(() -> snapshotControllers.webhook().ado(payload))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("read-only");
        }

        // The guard fires before BoardPort.onWebhook (board), the ingestion ledger, or a workflow.
        verify(snapshotBoard, never()).onWebhook(anyString(), any());
        verify(featureWorkflowStarter, never()).start(any());
        assertThat(ingestedCount("local", "demo-story")).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Read routes: snapshot item succeeds WITHOUT touching Temporal.
    // ---------------------------------------------------------------------------------------------

    @Test
    void snapshotReadRoutesSucceedWithoutTouchingTemporal() {
        var detail = snapshotControllers.items().get(snapStoryId);
        assertThat(detail.title()).isEqualTo("Snapshot story title");
        assertThat(detail.snapshot()).isNotNull();
        assertThat(detail.snapshot().key()).isEqualTo("demo-06b-story");

        List<ItemSummaryDto> summaries = snapshotControllers.items().list();
        assertThat(summaries).hasSize(4);
        assertThat(summaries.stream().filter(s -> s.snapshot() != null)).hasSize(2);

        assertThat(snapshotControllers.items().boardComments(snapStoryId)).isEmpty();

        QualityReportDto quality = snapshotControllers.items().quality(snapStoryId);
        assertThat(quality.verdict()).isEqualTo("failed");
        assertThat(quality.score()).isEqualTo(52);

        assertThat(snapshotControllers.items().reviewMd(snapStoryId).getBody()).isEqualTo("# curated snapshot review");

        var grill = snapshotControllers.items().grill(snapFeatureId);
        assertThat(grill.resolved()).isTrue();
        assertThat(grill.questions()).isEmpty();

        assertThat(snapshotControllers.release().documents(snapStoryId).getBody()).isEmpty();

        // Strongest assertion in the class: no snapshot read or mutation reached the Temporal stub.
        verify(snapshotStubs, never()).featureWorkflow(any());
    }

    // ---------------------------------------------------------------------------------------------
    // Ordinary live (non-snapshot) item: guard passes, normal path (incl. Temporal) is reached.
    // ---------------------------------------------------------------------------------------------

    @Test
    void liveItemMutationsAreNotRejectedAsSnapshots() {
        assertNotRejectedAsSnapshot(() -> liveControllers.items().approve(liveStoryId, new ApproveRequest("note"), httpRequest));
        assertNotRejectedAsSnapshot(() -> liveControllers.items().requestChanges(liveStoryId, httpRequest));
        assertNotRejectedAsSnapshot(() -> liveControllers.items().answerGrillQuestion(liveStoryId, "q1", new GrillAnswerRequest("answer"), httpRequest));
        assertNotRejectedAsSnapshot(() -> liveControllers.items().parkGrillQuestion(liveStoryId, "q1", httpRequest));
        assertNotRejectedAsSnapshot(() -> liveControllers.artifacts().addComment(liveStoryId,
                new CommentRequest("line:1", "comment", "change", true), httpRequest));
        assertNotRejectedAsSnapshot(() -> liveControllers.artifacts().approveAgentResult(liveStoryId, snapCommentId, httpRequest));
        assertNotRejectedAsSnapshot(() -> liveControllers.pr().approve(liveStoryId, new ApproveRequest(null), httpRequest));
        assertNotRejectedAsSnapshot(() -> liveControllers.pr().requestChanges(liveStoryId, httpRequest));
        assertNotRejectedAsSnapshot(() -> liveControllers.release().sign(liveStoryId, "doc-1", new ApproveRequest(null), httpRequest));
        assertNotRejectedAsSnapshot(() -> liveControllers.release().requestChanges(liveStoryId, httpRequest));

        // The live story routes that gate on a workflow DID reach the Temporal stub.
        verify(liveStubs, atLeastOnce()).featureWorkflow(any());
    }

    @Test
    void liveItemReadReachesWorkflowStubs() {
        var detail = liveControllers.items().get(liveStoryId);
        assertThat(detail.title()).isEqualTo("Live story title");
        verify(liveStubs, atLeastOnce()).featureWorkflow(any());
    }

    @Test
    void liveWebhookReachesBoardAndLedger() {
        liveControllers.webhook().local(Map.of("boardId", "live-feature", "kind", "item.updated", "rev", 1));
        verify(liveBoard, atLeastOnce()).onWebhook(anyString(), any());
        assertThat(ingestedCount("local", "live-feature")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void assertRejectedAsSnapshot(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        StateSnapshot before = captureState();
        assertThatThrownBy(call)
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("read-only");
        assertStateUnchanged(before);
        verify(snapshotStubs, never()).featureWorkflow(any());
        verify(repo, never()).writeFiles(anyString(), any(), anyString(), anyString());
        verify(snapshotBoard, never()).onWebhook(anyString(), any());
        verify(snapshotBoard, never()).addComment(any(), anyString(), anyString());
        verify(snapshotBoard, never()).transition(any(), any());
        verify(snapshotBoard, never()).createItem(anyString(), anyString(), any(), anyString());
        verify(snapshotBoard, never()).updateFields(any(), any());
    }

    private void assertNotRejectedAsSnapshot(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isNotInstanceOf(ConflictException.class);
    }

    private StateSnapshot captureState() {
        return new StateSnapshot(
                toList(workItems.findAll()),
                toList(artifacts.findAll()),
                toList(comments.findAll()),
                toList(approvals.findAll()),
                toList(reviewEvents.findAll()));
    }

    private void assertStateUnchanged(StateSnapshot before) {
        assertThat(toList(workItems.findAll())).containsExactlyInAnyOrderElementsOf(before.workItems());
        assertThat(toList(artifacts.findAll())).containsExactlyInAnyOrderElementsOf(before.artifacts());
        assertThat(toList(comments.findAll())).containsExactlyInAnyOrderElementsOf(before.comments());
        assertThat(toList(approvals.findAll())).containsExactlyInAnyOrderElementsOf(before.approvals());
        assertThat(toList(reviewEvents.findAll())).containsExactlyInAnyOrderElementsOf(before.reviewEvents());
    }

    private static <T> List<T> toList(Iterable<T> iterable) {
        List<T> out = new java.util.ArrayList<>();
        iterable.forEach(out::add);
        return out;
    }

    private int ingestedCount(String profile, String itemId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ingested_events WHERE profile = ? AND item_id = ?",
                Integer.class, profile, itemId);
        return count == null ? 0 : count;
    }
}
