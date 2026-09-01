package ai.pdlc.controlplane.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real {@code V1__schema.sql} Flyway migration and exercises every Spring Data JDBC
 * repository against a genuine Postgres 16 container - the schema uses Postgres-only syntax
 * ({@code gen_random_uuid()}, {@code ON CONFLICT}), so this deliberately does not use H2.
 *
 * <p>Spring Boot 4 dropped the {@code @DataJdbcTest} slice, so this wires a minimal context
 * ({@code @EnableAutoConfiguration} + component scan of this package, which is exactly where the
 * repository interfaces live) instead of the full application (which would also need
 * {@code PdlcConfig}/adapter/Temporal beans this test doesn't exercise).
 */
@Testcontainers
@SpringBootTest(classes = PersistenceIntegrationTest.TestApp.class)
class PersistenceIntegrationTest {

    @Configuration
    @EnableAutoConfiguration
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

    @org.junit.jupiter.api.BeforeAll
    static void migrate() {
        // Run Flyway explicitly rather than relying on Boot autoconfiguration timing relative to
        // @DynamicPropertySource - this guarantees the schema exists before any repository call.
        org.flywaydb.core.Flyway.configure()
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
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void migrationAppliesAndFullWriteReadCycleWorksAcrossAllTables() {
        IngestedEventStore ingestedEvents = new IngestedEventStore(jdbcTemplate);

        WorkItemEntity feature = workItems.save(WorkItemEntity.newRow("local", "in-memory", "4412", "feature", null, "new", null));
        assertThat(feature.id()).isNotNull();

        WorkItemEntity story = workItems.save(WorkItemEntity.newRow("local", "in-memory", "4413", "story", "4412",
                "awaiting-G1", "openspec/changes/export-orders-csv"));

        Optional<WorkItemEntity> found = workItems.findByProfileAndBoardId("local", "4413");
        assertThat(found).isPresent();
        assertThat(found.get().parentId()).isEqualTo("4412");

        ArtifactEntity artifact = artifacts.save(ArtifactEntity.newRow(story.id(), "story", 1, "abc123", "sha-v1", "po-agent-bot"));
        assertThat(artifacts.findByWorkItemIdAndVersion(story.id(), 1)).isPresent();

        CommentEntity comment = comments.save(CommentEntity.newRow(artifact.id(), 1, "squad-lead@acme", "SquadLead",
                "{\"line\":13}", "make it 20/hour for admin", "change", true));
        assertThat(comments.findByArtifactIdAndBlockingTrueAndResolvedInVersionIsNull(artifact.id())).hasSize(1);

        comments.save(comment.resolvedIn(2, "resolved in v2"));
        assertThat(comments.findByArtifactIdAndBlockingTrueAndResolvedInVersionIsNull(artifact.id())).isEmpty();

        approvals.save(ApprovalEntity.newRow(artifact.id(), 1, "abc123", "po@acme", "PO", "story", OffsetDateTime.now()));
        assertThat(approvals.findByArtifactIdAndVersion(artifact.id(), 1)).hasSize(1);

        reviewEvents.save(ReviewEventEntity.newRow(story.id(), "drafted", "{}"));
        reviewEvents.save(ReviewEventEntity.newRow(story.id(), "gate-passed", "{\"version\":2}"));
        List<ReviewEventEntity> events = reviewEvents.findByWorkItemIdOrderByTs(story.id());
        assertThat(events).extracting(ReviewEventEntity::kind).containsExactly("drafted", "gate-passed");

        assertThat(ingestedEvents.recordIfNew("local", "4412", 1, "item.created")).isTrue();
        assertThat(ingestedEvents.recordIfNew("local", "4412", 1, "item.created")).isFalse(); // dedupe
        assertThat(ingestedEvents.recordIfNew("local", "4412", 2, "comment.added")).isTrue();
    }

    @Test
    void workItemUniqueConstraintPreventsDuplicateProfileAndBoardId() {
        workItems.save(WorkItemEntity.newRow("local", "in-memory", "9001", "feature", null, "new", null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                workItems.save(WorkItemEntity.newRow("local", "in-memory", "9001", "feature", null, "new", null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
