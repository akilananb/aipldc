package ai.pdlc.controlplane.temporal;

import ai.pdlc.controlplane.persistence.ArtifactRepository;
import ai.pdlc.controlplane.persistence.CommentRepository;
import ai.pdlc.controlplane.persistence.PrRepository;
import ai.pdlc.controlplane.persistence.ReleaseDocumentRepository;
import ai.pdlc.controlplane.persistence.QualityReportRepository;
import ai.pdlc.controlplane.persistence.ReviewEventRepository;
import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.review.ReviewTrailService;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.CiPort;
import ai.pdlc.core.port.RepoPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression test for a real bug found in build-order phase 4's live e2e run: {@link
 * BoardSideEffectsImpl#fileMonitorCards} called {@link BoardPort#createItem} but never persisted
 * the created item to the {@code work_items} table (unlike {@code publishTasks}/{@code
 * publishReleasePack}, which both call {@code ensureWorkItem}) - the trip card existed only in the
 * in-memory board adapter, so {@code GET /api/items} (DB-backed) never showed it.
 */
@Testcontainers
@SpringBootTest(classes = BoardSideEffectsImplTest.TestApp.class)
class BoardSideEffectsImplTest {

    @Configuration
    @EnableAutoConfiguration
    @EnableJdbcRepositories(basePackages = "ai.pdlc.controlplane.persistence")
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
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
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
    PrRepository prs;
    @Autowired
    ReleaseDocumentRepository releaseDocuments;
    @Autowired
    ReviewEventRepository reviewEvents;
    @Autowired
    QualityReportRepository qualityReports;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void fileMonitorCardsPersistsTheTripItemToWorkItems() {
        workItems.save(WorkItemEntity.newRow("local", "in-memory", "4412", "story", "4411", "done", null));
        WorkItemRef story = new WorkItemRef("local", "4412");

        BoardPort board = Mockito.mock(BoardPort.class);
        when(board.createItem(anyString(), anyString(), any(), anyString()))
                .thenReturn(new WorkItem("9001", "bug", "Monitor trip: export-error-rate", "evidence",
                        CanonicalState.NEW, "4412", null, List.of()));

        PdlcConfig pdlcConfig = Mockito.mock(PdlcConfig.class);
        BoardConfig boardConfig = new BoardConfig("in-memory", "local", "PDLC", Map.of(), Map.of(), null);
        Profile profile = new Profile("local", boardConfig, null, null, null, Map.of());
        when(pdlcConfig.profile("local")).thenReturn(profile);
        RepoPort repo = Mockito.mock(RepoPort.class);
        CiPort ci = Mockito.mock(CiPort.class);
        ReviewTrailService reviewTrail = new ReviewTrailService(pdlcConfig, repo, reviewEvents);

        BoardSideEffectsImpl impl = new BoardSideEffectsImpl(pdlcConfig, board, repo, ci,
                workItems, artifacts, comments, reviewTrail, prs, releaseDocuments, qualityReports, jdbc);

        Handoff envelope = new Handoff("monitor-agent", "grill-agent", "4412", CanonicalState.DONE,
                List.of(), 0.9, List.of(), List.of());
        MonitorHandoff monitor = new MonitorHandoff(envelope, List.of(
                new MonitorHandoff.Trip("export-error-rate", "observed=3.1, threshold=> 2%", "bug", "PO")));

        impl.fileMonitorCards(story, monitor);

        var persisted = workItems.findByProfileAndBoardId("local", "9001");
        assertThat(persisted).isPresent();
        assertThat(persisted.get().kind()).isEqualTo("bug");
        assertThat(persisted.get().parentId()).isEqualTo("4412");
    }
}
