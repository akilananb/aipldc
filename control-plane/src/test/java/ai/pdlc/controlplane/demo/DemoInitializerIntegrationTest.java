package ai.pdlc.controlplane.demo;

import ai.pdlc.adapters.localgit.LocalGitRepoAdapter;
import ai.pdlc.controlplane.config.PortRegistry;
import ai.pdlc.controlplane.persistence.ApprovalRepository;
import ai.pdlc.controlplane.persistence.ArtifactEntity;
import ai.pdlc.controlplane.persistence.ArtifactRepository;
import ai.pdlc.controlplane.persistence.CommentEntity;
import ai.pdlc.controlplane.persistence.CommentRepository;
import ai.pdlc.controlplane.persistence.QualityReportRepository;
import ai.pdlc.controlplane.persistence.ReleaseDocumentRepository;
import ai.pdlc.controlplane.persistence.ReviewEventRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.web.dto.ReviewStateDto;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.ProjectMeta;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.Anchor;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.port.RepoPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link DemoInitializer}'s crash-recovery, idempotency and
 * non-destructive-on-existing-data guarantees, against a REAL temporary git repository (via
 * {@link LocalGitRepoAdapter} on a {@code git init}'d temp dir whose initial commit is our own
 * controlled "baseline") and REAL Postgres 16 (Testcontainers, all Flyway migrations V1..V10).
 *
 * <p>{@code DemoInitializer.run()} hardcodes the classpath resource {@code demo/restaurant-demo.json}
 * whose {@code baselineSha} points at the real restaurant-service checkout, so this test cannot drive
 * {@code run()} directly. Instead it uses the package-private {@link DemoInitializer#seed(String,
 * DemoManifest)} seam (an additive, backward-compatible extraction of the real {@code run()} body) to
 * seed a tiny hand-built 1-group/2-item manifest whose {@code baselineSha} is this test's own git
 * baseline commit. Each {@code @Test} method wipes the shared container's tables in {@code
 * #cleanTables()} via {@link #setUp} semantics (see {@code @BeforeEach}) and builds a fresh temp git
 * repo, unless the scenario explicitly shares state across two {@code DemoInitializer} instances.
 */
@Testcontainers
@SpringBootTest(classes = DemoInitializerIntegrationTest.TestApp.class)
class DemoInitializerIntegrationTest {

    @Configuration
    @EnableAutoConfiguration
    @EnableJdbcRepositories(basePackages = "ai.pdlc.controlplane")
    static class TestApp {
    }

    private static final String PROFILE = "test-local";
    private static final String SEED_VERSION = "test-restaurant-v1";
    private static final String STORY_BOARD_ID = "demo-06b-story";
    private static final String FEATURE_BOARD_ID = "demo-06b-feature";
    private static final String LIVE_BOARD_ID = "demo-live";
    private static final String SPEC_PATH = "openspec/changes/preserve-test";
    private static final String BRANCH = "demo/restaurant-v1/06b/v1";
    private static final String PROPOSAL_CONTENT = "### proposal v1\nPreserve the customer's customizations.\n";
    private static final String CHANGE_NOTES_CONTENT = "## Change notes\nCarry item, bun, omit, spice and deliverBy verbatim.\n";

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
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        jdbc.update("""
                TRUNCATE TABLE
                    local_board_webhooks,
                    local_board_comments,
                    local_board_items,
                    demo_seeds,
                    demo_snapshots,
                    work_items
                CASCADE
                """);
    }

    @Autowired
    DataSource dataSource;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JdbcAggregateTemplate aggregateTemplate;
    @Autowired
    WorkItemRepository workItems;
    @Autowired
    ArtifactRepository artifacts;
    @Autowired
    CommentRepository comments;
    @Autowired
    ApprovalRepository approvals;
    @Autowired
    QualityReportRepository qualityReports;
    @Autowired
    ReleaseDocumentRepository releaseDocuments;
    @Autowired
    ReviewEventRepository reviewEvents;
    @Autowired
    DemoSnapshotRepository demoSnapshots;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final List<Path> tempDirs = new ArrayList<>();

    @AfterEach
    void cleanupTempDirs() {
        for (Path dir : tempDirs) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort temp cleanup
                    }
                });
            } catch (IOException ignored) {
                // best-effort temp cleanup
            }
        }
    }

    // ---- fixtures --------------------------------------------------------------------------

    private record GitRepo(Path dir, String baselineSha, RepoPort repo) {
    }

    private GitRepo newGitRepo() throws Exception {
        Path dir = Files.createTempDirectory("pdlc-demo-init-it-");
        tempDirs.add(dir);
        runGit(dir, "init", "-q");
        runGit(dir, "config", "user.email", "it@example.com");
        runGit(dir, "config", "user.name", "pdlc-it");
        Files.writeString(dir.resolve("README.md"), "baseline\n", StandardCharsets.UTF_8);
        runGit(dir, "add", "README.md");
        runGit(dir, "commit", "-q", "-m", "baseline");
        String baselineSha = runGit(dir, "rev-parse", "HEAD").strip();
        return new GitRepo(dir, baselineSha, new LocalGitRepoAdapter(dir.toString()));
    }

    private String makeSecondCommit(Path dir) throws Exception {
        Files.writeString(dir.resolve("OTHER.md"), "other\n", StandardCharsets.UTF_8);
        runGit(dir, "add", "OTHER.md");
        runGit(dir, "commit", "-q", "-m", "second");
        return runGit(dir, "rev-parse", "HEAD").strip();
    }

    private static String runGit(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }

    /** Tiny 1-group/2-item manifest: one feature (parent) + one story with an artifact version, a
     * G1 gate (one artifact-version approval + one doc-id approval) and one release document. */
    private DemoManifest goodManifest(String baselineSha) {
        Instant t = Instant.parse("2026-09-14T10:00:00Z");

        DemoWorkItemFixture feature = new DemoWorkItemFixture(
                FEATURE_BOARD_ID, "feature", "Feature title", "Feature desc", null,
                CanonicalState.READY_FOR_STORY, null,
                List.of(), null, null, List.of(), List.of(), List.of(), List.of(), null);

        DemoCommentFixture blocking = new DemoCommentFixture(
                0, "lead@bistro", "SquadLead", "story", "scenario:test", "blocking note", "change",
                true, 1, null, null, null, null, null);
        DemoApprovalFixture versionApproval = new DemoApprovalFixture("po@bistro", "PO", 1, null, t);

        DemoVersionFixture v1 = new DemoVersionFixture(1, Map.of("proposal.md", PROPOSAL_CONTENT),
                List.of(blocking), List.of(versionApproval));

        DemoApprovalFixture gatePo = new DemoApprovalFixture("po@bistro", "PO", 1, null, t);
        DemoApprovalFixture gateLead = new DemoApprovalFixture("lead@bistro", "SquadLead", null, "change-notes", t);
        DemoGateFixture gate = new DemoGateFixture("G1", 1, List.of(gatePo, gateLead), 0);

        DemoReleaseDocumentFixture changeNotes = new DemoReleaseDocumentFixture(
                "change-notes", "Change notes", CHANGE_NOTES_CONTENT, "PO");

        DemoWorkItemFixture story = new DemoWorkItemFixture(
                STORY_BOARD_ID, "story", "Story title", "Story desc", FEATURE_BOARD_ID,
                CanonicalState.AWAITING_G1, SPEC_PATH,
                List.of(v1), gate, null, List.of(), List.of(), List.of(changeNotes), List.of(), null);

        DemoSnapshotGroup group = new DemoSnapshotGroup("06b", "Test snapshot", 1, "test@baseline", false,
                List.of(feature, story));

        DemoLiveSeed live = new DemoLiveSeed(LIVE_BOARD_ID, "Live feature", "Live desc", "orders", "resolved answer");

        return new DemoManifest(SEED_VERSION, baselineSha, live, List.of(group));
    }

    private DemoInitializer newInitializer(RepoPort repo, String profileName) {
        Profile profile = new Profile(profileName,
                new ProjectMeta(profileName, profileName, null, List.of(), "", null),
                new BoardConfig("local-jdbc", "org", "project", Map.of(), Map.of(), null),
                List.of(new RepoConfig("main", "local-git", "test-repo-path", "main", "openspec", List.of(), true)),
                new NotifyConfig("none", null),
                new AgentsConfig(null, null, Map.of()),
                Map.of());
        ProjectDirectory projects = mock(ProjectDirectory.class);
        when(projects.project(profileName)).thenReturn(profile);
        PortRegistry ports = mock(PortRegistry.class);
        when(ports.primaryRepo(profileName)).thenReturn(repo);
        return new DemoInitializer(dataSource, txManager, jdbc, aggregateTemplate,
                workItems, artifacts, comments, approvals, qualityReports, releaseDocuments, reviewEvents,
                ports, projects, profileName);
    }

    private int demoSeedsCount() {
        return jdbc.queryForObject("SELECT count(*) FROM demo_seeds", Integer.class);
    }

    private int workItemsCount() {
        return jdbc.queryForObject("SELECT count(*) FROM work_items", Integer.class);
    }

    private int demoSnapshotsCount() {
        return jdbc.queryForObject("SELECT count(*) FROM demo_snapshots", Integer.class);
    }

    private List<WorkItemEntity> allWorkItemsSorted() {
        return StreamSupport.stream(workItems.findAll().spliterator(), false)
                .sorted(Comparator.comparing(WorkItemEntity::boardId))
                .toList();
    }

    private List<ArtifactEntity> allArtifactsSorted() {
        return StreamSupport.stream(artifacts.findAll().spliterator(), false)
                .sorted(Comparator.comparing(ArtifactEntity::id))
                .toList();
    }

    // ---- tests -----------------------------------------------------------------------------

    @Test
    void initialSeedIsIdempotentAndDeterministic() throws Exception {
        GitRepo git = newGitRepo();
        DemoManifest manifest = goodManifest(git.baselineSha());
        String manifestRaw = mapper.writeValueAsString(manifest);

        newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest);

        assertThat(workItemsCount()).isEqualTo(3); // live + feature + story
        assertThat(demoSnapshotsCount()).isEqualTo(2); // feature + story (live has no snapshot row)
        assertThat(demoSeedsCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifacts", Integer.class)).isEqualTo(1);

        // Deterministic UUIDs: the story id is UUID.nameUUIDFromBytes over the fixed entity key.
        WorkItemEntity story = workItems.findByProfileAndBoardId(PROFILE, STORY_BOARD_ID).orElseThrow();
        assertThat(story.id()).isEqualTo(java.util.UUID.nameUUIDFromBytes(
                ("restaurant-v1:" + SEED_VERSION + ":item:" + STORY_BOARD_ID).getBytes(StandardCharsets.UTF_8)));

        List<WorkItemEntity> itemsBefore = allWorkItemsSorted();
        List<ArtifactEntity> artifactsBefore = allArtifactsSorted();
        String seedHashBefore = jdbc.queryForObject(
                "SELECT manifest_hash FROM demo_seeds WHERE profile = ? AND seed_version = ?", String.class,
                PROFILE, SEED_VERSION);

        // Second, FRESH DemoInitializer instance against the same DataSource/RepoPort/manifest.
        newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest);

        assertThat(workItemsCount()).isEqualTo(3);
        assertThat(demoSnapshotsCount()).isEqualTo(2);
        assertThat(demoSeedsCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifacts", Integer.class)).isEqualTo(1);

        String seedHashAfter = jdbc.queryForObject(
                "SELECT manifest_hash FROM demo_seeds WHERE profile = ? AND seed_version = ?", String.class,
                PROFILE, SEED_VERSION);
        assertThat(seedHashAfter).isEqualTo(seedHashBefore);

        // Every captured id/hash/state is byte-identical on re-query.
        assertThat(allWorkItemsSorted()).isEqualTo(itemsBefore);
        assertThat(allArtifactsSorted()).isEqualTo(artifactsBefore);
    }

    @Test
    void ordinaryItemAndCommentSurviveReseed() throws Exception {
        GitRepo git = newGitRepo();
        DemoManifest manifest = goodManifest(git.baselineSha());
        String manifestRaw = mapper.writeValueAsString(manifest);

        newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest);

        // A brand-new, NON-demo work item + a comment on its artifact, between the two seeds.
        WorkItemEntity ordinary = workItems.save(WorkItemEntity.newRow(PROFILE, "local-jdbc", "ordinary-1",
                "story", null, "new", "openspec/changes/ordinary"));
        ArtifactEntity ordArtifact = artifacts.save(ArtifactEntity.newRow(ordinary.id(), "story", 1,
                "ord-hash", "ord-sha", "tester"));
        CommentEntity ordComment = comments.save(CommentEntity.newRow(ordArtifact.id(), 1, "tester@x", "PO",
                null, "ordinary note", "note", false));

        int demoItemsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM work_items WHERE board_id LIKE 'demo-%'", Integer.class);
        int snapshotsBefore = demoSnapshotsCount();

        newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest);

        // Ordinary rows are completely unaffected.
        WorkItemEntity refetched = workItems.findByProfileAndBoardId(PROFILE, "ordinary-1").orElseThrow();
        assertThat(refetched.kind()).isEqualTo("story");
        assertThat(refetched.canonicalState()).isEqualTo("new");
        assertThat(refetched.specChangePath()).isEqualTo("openspec/changes/ordinary");
        List<CommentEntity> refetchedComments = comments.findByArtifactIdOrderByCreatedAt(ordArtifact.id());
        assertThat(refetchedComments).hasSize(1);
        assertThat(refetchedComments.get(0).text()).isEqualTo("ordinary note");
        assertThat(refetchedComments.get(0).blocking()).isFalse();

        // No duplicate demo rows.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM work_items WHERE board_id LIKE 'demo-%'", Integer.class)).isEqualTo(demoItemsBefore);
        assertThat(demoSnapshotsCount()).isEqualTo(snapshotsBefore);
        assertThat(demoSeedsCount()).isEqualTo(1);
    }

    @Test
    void failedRelationalImportRollsBackCleanlyAndDoesNotBlockRetry() throws Exception {
        GitRepo git = newGitRepo();
        DemoManifest manifest = goodManifest(git.baselineSha());
        String manifestRaw = mapper.writeValueAsString(manifest);

        // Force the story's local_board_items INSERT to violate its PK partway through the relational
        // import (after the live feature + feature rows were already inserted in the same transaction).
        jdbc.update("""
                INSERT INTO local_board_items (profile, board_id, kind, title, description, canonical_state,
                    parent_id, area_path, idempotency_key, rev, comment_seq)
                VALUES (?,?,?,?,?,?,?,?,NULL,0,0)
                """, PROFILE, STORY_BOARD_ID, "story", "conflict", "", "new", null, "orders");

        assertThatThrownBy(() -> newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest))
                .isInstanceOf(DataIntegrityViolationException.class);

        // No partial DB state survived the rolled-back transaction: no success marker, no work items,
        // no snapshot rows.
        assertThat(demoSeedsCount()).isZero();
        assertThat(workItemsCount()).isZero();
        assertThat(demoSnapshotsCount()).isZero();

        // Git phase (outside the transaction) did complete: the owned branch exists with its embedded
        // marker at the correct manifest hash - recoverable, not a foreign clobber.
        assertThat(git.repo().resolveRef(BRANCH)).isNotBlank();
        assertThat(git.repo().readFile(BRANCH, ".pdlc-demo-seed.json"))
                .contains(SEED_VERSION).contains("06b");

        // Resolve the conflict and retry with the SAME manifest: not blocked by any stale hash marker.
        jdbc.update("DELETE FROM local_board_items WHERE profile = ? AND board_id = ?", PROFILE, STORY_BOARD_ID);
        newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest);

        assertThat(demoSeedsCount()).isEqualTo(1);
        assertThat(workItemsCount()).isEqualTo(3);
        assertThat(demoSnapshotsCount()).isEqualTo(2);
    }

    @Test
    void foreignBranchAtDemoNameIsRefusedNotClobbered() throws Exception {
        GitRepo git = newGitRepo();
        String secondSha = makeSecondCommit(git.dir());
        git.repo().createBranch(secondSha, BRANCH);

        DemoManifest manifest = goodManifest(git.baselineSha());
        String manifestRaw = mapper.writeValueAsString(manifest);

        assertThatThrownBy(() -> newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to overwrite");

        // The foreign branch was not moved, and nothing was seeded.
        assertThat(git.repo().resolveRef(BRANCH)).isEqualTo(secondSha);
        assertThat(demoSeedsCount()).isZero();
        assertThat(workItemsCount()).isZero();
    }

    @Test
    void pristineBaselineBranchWithoutMarkerIsRecovered() throws Exception {
        GitRepo git = newGitRepo();
        // Simulate a crash between createBranch and writeFiles: reserved branch AT the pristine
        // baseline, carrying no marker.
        git.repo().createBranch(git.baselineSha(), BRANCH);

        DemoManifest manifest = goodManifest(git.baselineSha());
        String manifestRaw = mapper.writeValueAsString(manifest);

        newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest);

        assertThat(demoSeedsCount()).isEqualTo(1);
        assertThat(workItemsCount()).isEqualTo(3);
        // The reserved branch was written onto (moved past baseline) and now carries its marker.
        assertThat(git.repo().resolveRef(BRANCH)).isNotEqualTo(git.baselineSha());
        assertThat(git.repo().readFile(BRANCH, ".pdlc-demo-seed.json")).contains(SEED_VERSION).contains("06b");
    }

    @Test
    void artifactHashesAndGateSignaturesMatchSignedContent() throws Exception {
        GitRepo git = newGitRepo();
        DemoManifest manifest = goodManifest(git.baselineSha());
        String manifestRaw = mapper.writeValueAsString(manifest);
        newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest);

        WorkItemEntity story = workItems.findByProfileAndBoardId(PROFILE, STORY_BOARD_ID).orElseThrow();

        // Artifact content hash == independently recomputed Anchor.hash of the proposal read back via git.
        ArtifactEntity artifact = artifacts.findByWorkItemIdAndVersion(story.id(), 1).orElseThrow();
        assertThat(artifact.contentHash()).isEqualTo(Anchor.hash(PROPOSAL_CONTENT));
        String readBack = git.repo().readFile(artifact.gitRef(), SPEC_PATH + "/proposal.md");
        assertThat(readBack).isEqualTo(PROPOSAL_CONTENT);
        assertThat(artifact.contentHash()).isEqualTo(Anchor.hash(readBack));

        // Frozen gate signatures embedded in demo_snapshots.gate_json: the doc-id approval hashes the
        // signed release document; the artifact-version approval hashes the proposal content.
        DemoSnapshotEntity snap = demoSnapshots.findById(story.id()).orElseThrow();
        ReviewStateDto gate = mapper.readValue(snap.gateJson(), ReviewStateDto.class);
        assertThat(gate.approvals().get("change-notes").contentHash()).isEqualTo(Anchor.hash(CHANGE_NOTES_CONTENT));
        assertThat(gate.approvals().get("PO").contentHash()).isEqualTo(Anchor.hash(PROPOSAL_CONTENT));
    }

    @Test
    void snapshotGitRefResolvesReviewMdAndSpecFiles() throws Exception {
        // Regression coverage for a real bug: demo_snapshots.git_ref must be captured AFTER the
        // review.md commit (writeReviewMdTrail), not the pre-review.md version commit - otherwise
        // ItemsController.reviewMd/specDocs 500 on a snapshot that has any events.
        GitRepo git = newGitRepo();
        Instant t = Instant.parse("2026-09-14T10:00:00Z");

        DemoWorkItemFixture feature = new DemoWorkItemFixture(
                FEATURE_BOARD_ID, "feature", "Feature title", "Feature desc", null,
                CanonicalState.READY_FOR_STORY, null,
                List.of(), null, null, List.of(), List.of(), List.of(), List.of(), null);

        DemoVersionFixture v1 = new DemoVersionFixture(1, Map.of("proposal.md", PROPOSAL_CONTENT), List.of(), List.of());
        DemoEventFixture draftEvent = new DemoEventFixture("draft",
                Map.of("version", 1, "investSummary", "pass", "dorUnmetSummary", "none"), t);

        DemoWorkItemFixture story = new DemoWorkItemFixture(
                STORY_BOARD_ID, "story", "Story title", "Story desc", FEATURE_BOARD_ID,
                CanonicalState.AWAITING_G1, SPEC_PATH,
                List.of(v1), null, null, List.of(), List.of(), List.of(), List.of(draftEvent), null);

        DemoSnapshotGroup group = new DemoSnapshotGroup("06b", "Test snapshot", 1, "test@baseline", false,
                List.of(feature, story));
        DemoLiveSeed live = new DemoLiveSeed(LIVE_BOARD_ID, "Live feature", "Live desc", "orders", "resolved answer");
        DemoManifest manifest = new DemoManifest(SEED_VERSION, git.baselineSha(), live, List.of(group));
        String manifestRaw = mapper.writeValueAsString(manifest);

        newInitializer(git.repo(), PROFILE).seed(manifestRaw, manifest);

        WorkItemEntity storyRow = workItems.findByProfileAndBoardId(PROFILE, STORY_BOARD_ID).orElseThrow();
        DemoSnapshotEntity snap = demoSnapshots.findById(storyRow.id()).orElseThrow();
        assertThat(snap.gitRef()).isNotBlank();

        // Every file a snapshot read path resolves against snap.gitRef() must actually exist there.
        String reviewMd = git.repo().readFile(snap.gitRef(), SPEC_PATH + "/review.md");
        assertThat(reviewMd).contains("review.md").contains("Curated demo snapshot");
        String proposal = git.repo().readFile(snap.gitRef(), SPEC_PATH + "/proposal.md");
        assertThat(proposal).isEqualTo(PROPOSAL_CONTENT);

        // The per-version artifact ref (used for the immutable diff view) is unaffected: it still
        // resolves the same proposal content, whether or not it equals snap.gitRef().
        ArtifactEntity artifact = artifacts.findByWorkItemIdAndVersion(storyRow.id(), 1).orElseThrow();
        assertThat(git.repo().readFile(artifact.gitRef(), SPEC_PATH + "/proposal.md")).isEqualTo(PROPOSAL_CONTENT);
    }
}
