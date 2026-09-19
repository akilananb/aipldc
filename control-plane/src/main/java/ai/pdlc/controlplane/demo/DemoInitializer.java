package ai.pdlc.controlplane.demo;

import ai.pdlc.controlplane.persistence.ApprovalEntity;
import ai.pdlc.controlplane.persistence.ApprovalRepository;
import ai.pdlc.controlplane.persistence.ArtifactEntity;
import ai.pdlc.controlplane.persistence.ArtifactRepository;
import ai.pdlc.controlplane.persistence.CommentEntity;
import ai.pdlc.controlplane.persistence.CommentRepository;
import ai.pdlc.controlplane.persistence.QualityReportEntity;
import ai.pdlc.controlplane.persistence.QualityReportRepository;
import ai.pdlc.controlplane.persistence.ReleaseDocumentEntity;
import ai.pdlc.controlplane.persistence.ReleaseDocumentRepository;
import ai.pdlc.controlplane.persistence.ReviewEventEntity;
import ai.pdlc.controlplane.persistence.ReviewEventRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.web.dto.GrillQuestionDto;
import ai.pdlc.controlplane.web.dto.GrillQuestionsDto;
import ai.pdlc.controlplane.web.dto.ReviewStateDto;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.Anchor;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.port.RepoPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Seeds the curated restaurant-demo catalog (44 work items: 8 read-only snapshot groups + the
 * separate live {@code demo-live} feature) into a fresh {@code local-jdbc}/{@code local-git}
 * profile on control-plane startup - plan step 4. Gated by {@code pdlc.demo.enabled} (default
 * {@code false}, see {@code application.yml}), so ado-pilot and every automated test that doesn't
 * opt in never seeds anything. {@link ApplicationRunner} beans run after Spring Boot's own
 * embedded Flyway {@code FlywayMigrationInitializer}, which is itself ordered before other
 * {@code InitializingBean}/{@code ApplicationRunner} beans by Spring Boot's autoconfiguration, so
 * this always observes the fully-migrated schema (V1..V10) without an explicit {@code @DependsOn}.
 *
 * <p>Two-phase, both idempotent and crash-recoverable:
 * <ol>
 *   <li>Materialize owned git branches ({@code demo/restaurant-v1/<key>/v<version>}), each guarded
 *   by an embedded {@code .pdlc-demo-seed.json} ownership marker so a retry after a crash between
 *   phases never clobbers a foreign branch and never re-writes an already-seeded one.</li>
 *   <li>One Spring-managed transaction inserts every relational row (work items, artifacts,
 *   comments, approvals, quality reports, release documents, review events, demo_snapshots) plus
 *   the {@code demo_seeds} marker row. A same-profile concurrent start is serialized by a
 *   PostgreSQL advisory lock held on one dedicated connection across both the seed decision and
 *   the transaction.</li>
 * </ol>
 *
 * <p>Local board rows (local_board_items/local_board_comments, owned by {@link
 * ai.pdlc.adapters.localboard.LocalBoardAdapter}) are imported with direct SQL rather than through
 * {@code BoardPort} - going through the port's webhook/create path would look like live board
 * activity and is unnecessary ceremony for a one-time, already-validated import.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "pdlc.demo.enabled", havingValue = "true")
public class DemoInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoInitializer.class);
    private static final String SEED_MARKER_FILE = ".pdlc-demo-seed.json";
    private static final String MANIFEST_RESOURCE = "demo/restaurant-demo.json";

    private final javax.sql.DataSource dataSource;
    private final PlatformTransactionManager txManager;
    private final JdbcTemplate jdbc;
    private final JdbcAggregateTemplate aggregateTemplate;
    private final WorkItemRepository workItems;
    private final ArtifactRepository artifacts;
    private final CommentRepository comments;
    private final ApprovalRepository approvals;
    private final QualityReportRepository qualityReports;
    private final ReleaseDocumentRepository releaseDocuments;
    private final ReviewEventRepository reviewEvents;
    private final RepoPort repo;
    private final Profile activeProfile;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public DemoInitializer(javax.sql.DataSource dataSource, PlatformTransactionManager txManager, JdbcTemplate jdbc,
                            JdbcAggregateTemplate aggregateTemplate, WorkItemRepository workItems, ArtifactRepository artifacts,
                            CommentRepository comments, ApprovalRepository approvals, QualityReportRepository qualityReports,
                            ReleaseDocumentRepository releaseDocuments, ReviewEventRepository reviewEvents,
                            RepoPort repo, Profile activeProfile) {
        this.dataSource = dataSource;
        this.txManager = txManager;
        this.jdbc = jdbc;
        this.aggregateTemplate = aggregateTemplate;
        this.workItems = workItems;
        this.artifacts = artifacts;
        this.comments = comments;
        this.approvals = approvals;
        this.qualityReports = qualityReports;
        this.releaseDocuments = releaseDocuments;
        this.reviewEvents = reviewEvents;
        this.repo = repo;
        this.activeProfile = activeProfile;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!"local-jdbc".equals(activeProfile.board().provider())) {
            throw new IllegalStateException("pdlc.demo.enabled=true requires board.provider: local-jdbc for profile '"
                    + activeProfile.name() + "' (found '" + activeProfile.board().provider() + "')");
        }
        if (!"local-git".equals(activeProfile.repo().provider())) {
            throw new IllegalStateException("pdlc.demo.enabled=true requires repo.provider: local-git for profile '"
                    + activeProfile.name() + "' (found '" + activeProfile.repo().provider() + "')");
        }

        String manifestRaw = readManifestResource();
        seed(manifestRaw, mapper.readValue(manifestRaw, DemoManifest.class));
    }

    /**
     * Seeds an already-loaded, already-parsed manifest. Extracted from {@link
     * #run(ApplicationArguments)} as a package-private seam so the integration test can drive the real
     * seed logic against a tiny hand-built manifest and a throwaway git repo/Postgres, without the
     * bundled classpath resource (whose {@code baselineSha} points at the real restaurant-service
     * checkout that a throwaway test repo does not contain). The public {@code run()} path used by real
     * startup is unchanged: it still loads {@code demo/restaurant-demo.json} from the classpath and
     * delegates to this method.
     */
    void seed(String manifestRaw, DemoManifest manifest) throws Exception {
        String manifestHash = Anchor.hash(manifestRaw);

        long lockKey = stableLockKey(activeProfile.name());
        try (Connection lockConn = dataSource.getConnection()) {
            acquireLock(lockConn, lockKey);
            try {
                Optional<String> existingHash = findSeedManifestHash(activeProfile.name(), manifest.version());
                if (existingHash.isPresent()) {
                    if (!existingHash.get().equals(manifestHash)) {
                        throw new IllegalStateException("Demo seed '" + manifest.version() + "' bundle changed for profile '"
                                + activeProfile.name() + "' (stored manifest hash=" + existingHash.get() + ", current="
                                + manifestHash + "); run scripts/demo.sh reset --yes for a clean reinitialization");
                    }
                    log.info("Demo already seeded for profile '{}' version '{}'; leaving existing catalog and live progress untouched",
                            activeProfile.name(), manifest.version());
                    return;
                }
                seedFresh(manifest, manifestHash);
                log.info("Demo seed '{}' complete for profile '{}'", manifest.version(), activeProfile.name());
            } finally {
                releaseLock(lockConn, lockKey);
            }
        }
    }

    // ---- manifest / seed-marker bookkeeping ----------------------------------------------

    private String readManifestResource() throws IOException {
        try (InputStream in = new ClassPathResource(MANIFEST_RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Optional<String> findSeedManifestHash(String profile, String seedVersion) {
        List<String> rows = jdbc.query("SELECT manifest_hash FROM demo_seeds WHERE profile = ? AND seed_version = ?",
                (rs, i) -> rs.getString(1), profile, seedVersion);
        return rows.stream().findFirst();
    }

    private static long stableLockKey(String profile) {
        // Fixed namespace (upper 32 bits) so this lock key space never collides with an unrelated
        // advisory lock some other part of the stack might take on the same profile string.
        return (0x504C4443L << 32) | (profile.hashCode() & 0xFFFFFFFFL);
    }

    private void acquireLock(Connection conn, long key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_lock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        }
    }

    private void releaseLock(Connection conn, long key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        }
    }

    // ---- phase 1: git ref materialization --------------------------------------------------

    private String branchName(String groupKey, int version) {
        return "demo/restaurant-v1/" + groupKey + "/v" + version;
    }

    /** Idempotent: returns the existing commit sha unchanged when this exact (group, version,
     * manifest) was already written by a prior crashed attempt; creates+writes fresh onto a new or
     * pristine-baseline branch otherwise; aborts loudly on any other conflicting/foreign ref. */
    private String ensureVersionBranch(DemoManifest manifest, String manifestHash, String groupKey, int version,
                                        String specChangePath, Map<String, String> files) {
        String branch = branchName(groupKey, version);
        String currentSha = tryResolveRef(branch);
        if (currentSha == null) {
            repo.createBranch(manifest.baselineSha(), branch);
        } else {
            String markerJson = tryReadFile(branch, SEED_MARKER_FILE);
            if (markerJson != null) {
                SeedMarker marker = parseMarker(markerJson);
                if (manifest.version().equals(marker.seedVersion()) && groupKey.equals(marker.key())
                        && version == marker.version() && manifestHash.equals(marker.manifestHash())) {
                    log.info("Branch {} already owned by this exact demo seed; reusing commit {}", branch, currentSha);
                    return currentSha;
                }
                throw new IllegalStateException("Branch " + branch + " is owned by a different demo seed (" + markerJson
                        + "); refusing to overwrite. Run scripts/demo.sh reset --yes.");
            }
            if (!currentSha.equals(manifest.baselineSha())) {
                throw new IllegalStateException("Branch " + branch + " already exists, carries no demo-seed marker, and is"
                        + " not at the pinned pristine baseline (" + manifest.baselineSha() + "); refusing to overwrite."
                        + " Remove or rename it before re-running the demo seed.");
            }
            // Markerless and pristine: a prior attempt reserved this branch via createBranch but
            // crashed before writeFiles landed. Safe to proceed and write onto it now.
        }

        Map<String, String> toWrite = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            toWrite.put(specChangePath + "/" + file.getKey(), file.getValue());
        }
        toWrite.put(SEED_MARKER_FILE, markerJsonFor(manifest.version(), groupKey, version, manifestHash));
        return repo.writeFiles(branch, toWrite, "demo seed " + groupKey + " v" + version, "pdlc-demo-init").sha();
    }

    /** Appends recorded build evidence (source-08 implementation/tests) onto an already-materialized
     * version branch, purely for provenance/browsability - the frozen artifact/demo_snapshots refs
     * recorded elsewhere always point at the pre-evidence commit, so this never changes what
     * ArtifactsController/specDocs read back for that version. */
    private void appendBuildEvidence(String groupKey, int version, DemoBuildEvidence evidence) {
        if (evidence == null) {
            return;
        }
        String branch = branchName(groupKey, version);
        repo.writeFiles(branch, evidence.files(), "demo seed " + groupKey + " build evidence: " + evidence.note(),
                "pdlc-demo-init");
    }

    private String tryResolveRef(String ref) {
        try {
            return repo.resolveRef(ref);
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    private String tryReadFile(String ref, String path) {
        try {
            return repo.readFile(ref, path);
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    private record SeedMarker(String seedVersion, String key, int version, String manifestHash) {
    }

    private String markerJsonFor(String seedVersion, String key, int version, String manifestHash) {
        try {
            return mapper.writeValueAsString(new SeedMarker(seedVersion, key, version, manifestHash));
        } catch (Exception e) {
            throw new IllegalStateException("Could not render demo seed marker", e);
        }
    }

    private SeedMarker parseMarker(String json) {
        try {
            return mapper.readValue(json, SeedMarker.class);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt demo seed marker: " + json, e);
        }
    }

    // ---- phase 2: relational import (single transaction) -----------------------------------

    private void seedFresh(DemoManifest manifest, String manifestHash) {
        // Phase 1: git refs, outside the DB transaction (git has no rollback story here; each
        // write is individually idempotent/ownership-checked, so a crash mid-phase is recoverable
        // on the next startup attempt without partial-state ambiguity).
        Map<String, String> versionCommitSha = new LinkedHashMap<>(); // "<groupKey>:<version>" -> sha
        for (DemoSnapshotGroup group : manifest.snapshots()) {
            for (DemoWorkItemFixture item : group.items()) {
                for (DemoVersionFixture version : item.versions()) {
                    String key = group.key() + ":" + version.version();
                    versionCommitSha.computeIfAbsent(key, k ->
                            ensureVersionBranch(manifest, manifestHash, group.key(), version.version(), item.specChangePath(), version.files()));
                }
                if (item.buildEvidence() != null) {
                    int latest = item.versions().stream().mapToInt(DemoVersionFixture::version).max()
                            .orElseThrow(() -> new IllegalStateException("buildEvidence present with no versions on " + item.boardId()));
                    appendBuildEvidence(group.key(), latest, item.buildEvidence());
                }
            }
        }

        // Phase 2: one transaction, everything else.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> importAll(manifest, manifestHash, versionCommitSha));
    }

    private void importAll(DemoManifest manifest, String manifestHash, Map<String, String> versionCommitSha) {
        AtomicLong clock = new AtomicLong(0); // deterministic, strictly-increasing row timestamps
        Instant base = Instant.parse("2026-09-14T09:00:00Z");

        // The separate, real live feature: local board row + work_items row only - no
        // demo_snapshots row, no artifacts/comments/gate, never touched again by this seed.
        seedLiveFeature(manifest.live());

        for (DemoSnapshotGroup group : manifest.snapshots()) {
            for (DemoWorkItemFixture item : group.items()) {
                UUID itemId = deterministicId(manifest.version(), "item", item.boardId());
                insertLocalBoardItem(item);
                aggregateTemplate.insert(new WorkItemEntity(itemId, activeProfile.name(), activeProfile.board().provider(),
                        item.boardId(), item.kind(), item.parentBoardId(), item.state().wireValue(), item.specChangePath(),
                        tick(base, clock), tick(base, clock)));

                String latestGitRef = null;
                Map<Integer, ArtifactEntity> artifactByVersion = new LinkedHashMap<>();
                for (DemoVersionFixture version : item.versions()) {
                    String sha = versionCommitSha.get(group.key() + ":" + version.version());
                    String proposalContent = version.files().get("proposal.md");
                    String contentHash = Anchor.hash(proposalContent == null ? "" : proposalContent);
                    UUID artifactId = deterministicId(manifest.version(), "artifact", item.boardId(), String.valueOf(version.version()));
                    ArtifactEntity artifact = new ArtifactEntity(artifactId, itemId, "story", version.version(), contentHash,
                            sha, "pdlc-demo-init", tick(base, clock));
                    aggregateTemplate.insert(artifact);
                    artifactByVersion.put(version.version(), artifact);
                    latestGitRef = sha;

                    int ordinal = 0;
                    for (DemoCommentFixture c : version.comments()) {
                        UUID commentId = deterministicId(manifest.version(), "comment", item.boardId(), String.valueOf(version.version()), String.valueOf(ordinal++));
                        aggregateTemplate.insert(new CommentEntity(commentId, artifactId, c.version(), c.by(), c.role(),
                                null, c.text(), c.intent(), c.blocking(), c.resolvedInVersion(), null, c.agentName(),
                                c.agentResultMd(), c.agentResultStatus(), c.agentResultApprovedBy(), tick(base, clock)));
                    }
                    for (DemoApprovalFixture a : version.approvals()) {
                        UUID approvalId = deterministicId(manifest.version(), "approval", item.boardId(), String.valueOf(version.version()), a.role());
                        aggregateTemplate.insert(new ApprovalEntity(approvalId, artifactId, version.version(), contentHash,
                                a.who(), a.role(), "story", a.at().atOffset(ZoneOffset.UTC)));
                    }
                }

                for (DemoQualityReportFixture q : item.qualityReports()) {
                    UUID qId = deterministicId(manifest.version(), "quality", item.boardId(), String.valueOf(q.version()));
                    aggregateTemplate.insert(new QualityReportEntity(qId, itemId, q.version(), q.subjectKind(),
                            q.passed() ? "passed" : "failed", q.score(), q.reportMd(), tick(base, clock)));
                }

                Map<String, String> releaseDocContent = new LinkedHashMap<>();
                String releaseId = "R-demo-" + group.key();
                int packVersion = item.gate() != null ? item.gate().version() : 1;
                for (DemoReleaseDocumentFixture d : item.releaseDocuments()) {
                    String hash = Anchor.hash(d.content());
                    releaseDocContent.put(d.docId(), d.content());
                    UUID docRowId = deterministicId(manifest.version(), "releasedoc", item.boardId(), d.docId());
                    aggregateTemplate.insert(new ReleaseDocumentEntity(docRowId, itemId, releaseId, d.docId(), d.title(),
                            d.content(), d.checkerRole(), hash, packVersion, tick(base, clock)));
                }

                for (DemoCommentFixture bc : item.boardComments()) {
                    insertLocalBoardComment(item.boardId(), bc);
                    if (bc.agentName() != null && !item.versions().isEmpty()) {
                        // Rich agent-mention rendering (approved markdown/status) has no column on
                        // local_board_comments; mirror it onto the story's latest spec-review
                        // comment table too, so ArtifactsController's comment feed shows the
                        // approved @-mention exactly like a live mention would.
                        int latestVersion = item.versions().stream().mapToInt(DemoVersionFixture::version).max().getAsInt();
                        ArtifactEntity latestArtifact = artifactByVersion.get(latestVersion);
                        UUID mentionId = deterministicId(manifest.version(), "mention", item.boardId(), bc.by());
                        aggregateTemplate.insert(new CommentEntity(mentionId, latestArtifact.id(), latestVersion, bc.by(),
                                bc.role(), null, bc.text(), bc.intent(), bc.blocking(), null, null, bc.agentName(),
                                bc.agentResultMd() == null ? bc.text() : bc.agentResultMd(), bc.agentResultStatus(),
                                bc.agentResultApprovedBy(), tick(base, clock)));
                    }
                }

                int eventIndex = 0;
                for (DemoEventFixture event : item.events()) {
                    UUID eventId = deterministicId(manifest.version(), "event", item.boardId(), String.valueOf(eventIndex++));
                    String payloadJson = writeJson(event.payload());
                    aggregateTemplate.insert(new ReviewEventEntity(eventId, itemId, event.timestamp().atOffset(ZoneOffset.UTC),
                            event.kind(), payloadJson));
                }
                String reviewMdSha = writeReviewMdTrail(item, group, manifest, versionCommitSha);
                if (reviewMdSha != null) {
                    latestGitRef = reviewMdSha; // demo_snapshots.git_ref must point past the review.md commit
                }

                String gateJson = item.gate() == null ? null
                        : writeJson(gateDtoFor(item, item.gate(), releaseDocContent));
                String grillJson = item.grill() == null ? null : writeJson(grillDtoFor(item.grill()));

                // demo_snapshots has a pre-assigned @Id (the work_item_id FK), so repository.save() would
                // treat it as an existing aggregate and issue a no-op UPDATE. Insert explicitly, like
                // every other entity in this method.
                aggregateTemplate.insert(new DemoSnapshotEntity(itemId, manifest.version(), group.key(), group.label(),
                        group.order(), group.sourceRef(), group.replay(), latestGitRef, gateJson, grillJson));
            }
        }

        jdbc.update("INSERT INTO demo_seeds (profile, seed_version, manifest_hash, initialized_at) VALUES (?,?,?, now())",
                activeProfile.name(), manifest.version(), manifestHash);
    }

    private void seedLiveFeature(DemoLiveSeed live) {
        UUID id = deterministicId("live", activeProfile.name(), live.boardId());
        jdbc.update("""
                INSERT INTO local_board_items (profile, board_id, kind, title, description, canonical_state,
                    parent_id, area_path, idempotency_key, rev, comment_seq)
                VALUES (?,?,?,?,?,?,?,?,NULL,0,0)
                ON CONFLICT (profile, board_id) DO NOTHING
                """,
                activeProfile.name(), live.boardId(), "feature", live.title(), live.description(),
                CanonicalState.NEW.wireValue(), null, live.areaPath());
        boolean exists = workItems.findByProfileAndBoardId(activeProfile.name(), live.boardId()).isPresent();
        if (!exists) {
            aggregateTemplate.insert(new WorkItemEntity(id, activeProfile.name(), activeProfile.board().provider(),
                    live.boardId(), "feature", null, CanonicalState.NEW.wireValue(), null,
                    OffsetDateTime.now(), OffsetDateTime.now()));
        }
    }

    private void insertLocalBoardItem(DemoWorkItemFixture item) {
        jdbc.update("""
                INSERT INTO local_board_items (profile, board_id, kind, title, description, canonical_state,
                    parent_id, area_path, idempotency_key, rev, comment_seq)
                VALUES (?,?,?,?,?,?,?,?,NULL,0,?)
                """,
                activeProfile.name(), item.boardId(), item.kind(), item.title(), item.description(),
                item.state().wireValue(), item.parentBoardId(), "orders", item.boardComments().size());
    }

    private void insertLocalBoardComment(String boardId, DemoCommentFixture c) {
        jdbc.update("""
                INSERT INTO local_board_comments (profile, board_id, id, ordinal, author, role, stage, target, text,
                    intent, blocking, version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                activeProfile.name(), boardId, boardId + "-c" + c.ordinal(), c.ordinal(), c.by(), c.role(), c.stage(),
                c.target(), c.text(), c.intent(), c.blocking(), c.version());
    }

    /** Returns the new commit sha review.md was written to, or {@code null} when this item has no
     * spec-review trail (feature/task/release/bug items, or a story with no events). */
    private String writeReviewMdTrail(DemoWorkItemFixture item, DemoSnapshotGroup group, DemoManifest manifest,
                                       Map<String, String> versionCommitSha) {
        if (item.specChangePath() == null || item.events().isEmpty()) {
            return null;
        }
        int latestVersion = item.versions().isEmpty() ? 0
                : item.versions().stream().mapToInt(DemoVersionFixture::version).max().getAsInt();
        String branch = latestVersion == 0 ? null : branchName(group.key(), latestVersion);
        if (branch == null) {
            return null;
        }
        StringBuilder md = new StringBuilder("# review.md — ").append(item.specChangePath()).append('\n');
        md.append("\n> Curated demo snapshot (").append(group.label()).append(" · ").append(group.sourceRef())
                .append("). This trail is a curated historical import, not a live agent run.\n");
        for (DemoEventFixture event : item.events()) {
            md.append(renderEvent(event));
        }
        String path = item.specChangePath() + "/review.md";
        return repo.writeFiles(branch, Map.of(path, md.toString()), "demo seed " + group.key() + " review.md", "pdlc-demo-init").sha();
    }

    @SuppressWarnings("unchecked")
    private String renderEvent(DemoEventFixture event) {
        Map<String, Object> p = event.payload();
        OffsetDateTime at = event.timestamp().atOffset(ZoneOffset.UTC);
        return switch (event.kind()) {
            case "draft" -> ai.pdlc.core.review.ReviewMdWriter.draftBlock(((Number) p.get("version")).intValue(), at,
                    String.valueOf(p.get("investSummary")), String.valueOf(p.get("dorUnmetSummary")));
            case "revision" -> ai.pdlc.core.review.ReviewMdWriter.revisionBlock(((Number) p.get("version")).intValue(), at,
                    (List<String>) p.getOrDefault("changeLines", List.of()), String.valueOf(p.get("investSummary")),
                    String.valueOf(p.get("dorSummary")), (List<String>) p.getOrDefault("resolvedRefs", List.of()));
            case "comment" -> ai.pdlc.core.review.ReviewMdWriter.commentBlock(String.valueOf(p.get("role")),
                    String.valueOf(p.get("target")), at, String.valueOf(p.get("text")));
            case "quality" -> ai.pdlc.core.review.ReviewMdWriter.qualityBlock(((Number) p.get("version")).intValue(),
                    String.valueOf(p.get("verdict")), ((Number) p.get("score")).intValue(), at);
            case "approval" -> ai.pdlc.core.review.ReviewMdWriter.approveBlock(String.valueOf(p.get("role")),
                    ((Number) p.get("version")).intValue(), at, String.valueOf(p.getOrDefault("note", "")));
            case "gate" -> ai.pdlc.core.review.ReviewMdWriter.gatePassedBlock(((Number) p.get("gate")).intValue(),
                    ((Number) p.get("version")).intValue());
            case "pr" -> ai.pdlc.core.review.ReviewMdWriter.prOpenedBlock(((Number) p.get("taskCount")).intValue(),
                    String.valueOf(p.get("branch")), String.valueOf(p.get("target")), String.valueOf(p.get("prUrl")),
                    (List<String>) p.getOrDefault("taskLines", List.of()), (List<String>) p.getOrDefault("findingLines", List.of()));
            case "release" -> ai.pdlc.core.review.ReviewMdWriter.releasePackBlock(String.valueOf(p.get("releaseId")),
                    (List<String>) p.getOrDefault("documentLines", List.of()));
            case "sign" -> ai.pdlc.core.review.ReviewMdWriter.documentSignedBlock(String.valueOf(p.get("role")),
                    String.valueOf(p.get("docId")), at);
            case "deploy" -> ai.pdlc.core.review.ReviewMdWriter.deployedBlock(String.valueOf(p.get("env")),
                    String.valueOf(p.get("releaseId")), String.valueOf(p.get("branch")), String.valueOf(p.get("runId")));
            case "monitor" -> ai.pdlc.core.review.ReviewMdWriter.monitorEvaluationBlock(
                    (List<String>) p.getOrDefault("tripLines", List.of()));
            case "agentResult" -> ai.pdlc.core.review.ReviewMdWriter.agentResultBlock(String.valueOf(p.get("agentName")),
                    String.valueOf(p.get("target")), at, String.valueOf(p.get("approvedBy")), String.valueOf(p.get("markdown")));
            default -> throw new IllegalStateException("Unknown demo event kind: " + event.kind());
        };
    }

    private ReviewStateDto gateDtoFor(DemoWorkItemFixture item, DemoGateFixture gate, Map<String, String> releaseDocContent) {
        Map<String, ReviewStateDto.ApprovalDto> approvalDtos = new LinkedHashMap<>();
        for (DemoApprovalFixture a : gate.approvals()) {
            String contentHash;
            if (a.docId() != null) {
                contentHash = Anchor.hash(releaseDocContent.getOrDefault(a.docId(), ""));
            } else {
                String proposal = item.versions().stream()
                        .filter(v -> v.version() == (a.artifactVersion() == null ? -1 : a.artifactVersion()))
                        .findFirst().map(v -> v.files().get("proposal.md")).orElse("");
                contentHash = Anchor.hash(proposal);
            }
            String key = a.docId() != null ? a.docId() : a.role();
            approvalDtos.put(key, new ReviewStateDto.ApprovalDto(a.who(), a.role(), gate.version(), contentHash,
                    a.at().toString()));
        }
        return new ReviewStateDto(gate.version(), approvalDtos, gate.openBlockingComments(), item.state().wireValue());
    }

    private GrillQuestionsDto grillDtoFor(DemoGrillFixture grill) {
        List<GrillQuestionDto> questions = grill.questions().stream()
                .map(q -> new GrillQuestionDto(q.id(), q.askedBy(), q.category(), q.question(), q.evidence(),
                        q.status(), q.answer(), q.answeredBy()))
                .toList();
        return new GrillQuestionsDto(grill.resolved(), 0, questions);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize demo seed JSON", e);
        }
    }

    private OffsetDateTime tick(Instant base, AtomicLong clock) {
        return base.plusSeconds(clock.getAndIncrement()).atOffset(ZoneOffset.UTC);
    }

    private static UUID deterministicId(String... parts) {
        String key = "restaurant-v1:" + String.join(":", parts);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
