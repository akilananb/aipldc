package ai.pdlc.adapters.projects;

import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.config.PdlcConfigException;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDocument;
import ai.pdlc.core.config.ProjectMeta;
import ai.pdlc.core.config.RepoConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link JdbcProjectDirectory} round-trips a {@link ProjectDocument} through the real
 * {@code projects} table (Postgres 16, Testcontainers) and merges deployment-only
 * {@code agents}/{@code notify} from the same-named {@code pdlc.yaml} profile when one exists,
 * else from the deployment profile. Follows {@code LocalMetricsAdapterTest}'s wiring: real
 * {@code postgres:16-alpine} + an explicit table create in {@code @BeforeAll} (the {@code projects}
 * table mirrors {@code control-plane}/V13__projects.sql).
 */
@Testcontainers
class JdbcProjectDirectoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String YAML = """
            profiles:
              local:
                board: { provider: in-memory, org: local, project: PDLC, types: {}, states: {}, auth: { kind: none, secret_ref: kv://none } }
                repo: { provider: local-git, url: /tmp/local, default_branch: main, spec_dir: openspec }
                notify: { provider: slack, channel: local-channel }
                agents: { gateway: http://local-gw, roles: { grill: { model: local-model } } }
                gates: { G1: { roles: [PO], sod: false } }
              deployment:
                board: { provider: in-memory, org: local, project: PDLC, types: {}, states: {}, auth: { kind: none, secret_ref: kv://none } }
                repo: { provider: local-git, url: /tmp/deploy, default_branch: main, spec_dir: openspec }
                notify: { provider: email, channel: deploy-channel }
                agents: { gateway: http://deploy-gw, roles: { grill: { model: deploy-model } } }
                gates: { G1: { roles: [PO], sod: false } }
            """;

    static DataSource dataSource;
    static JdbcProjectDirectory directory;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE projects (
                        id           TEXT PRIMARY KEY,
                        name         TEXT NOT NULL,
                        config_json  TEXT NOT NULL,
                        created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_by   TEXT
                    )
                    """);
        }
        PdlcConfig deployment = PdlcConfig.load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)));
        directory = new JdbcProjectDirectory(dataSource, deployment, "deployment", new ObjectMapper());
    }

    @Test
    void saveThenProjectAndFindRoundTripTheDocument() {
        ProjectDocument doc = projectDoc("local", "Local project");
        directory.save("local", doc, "admin@acme");

        Profile profile = directory.project("local");
        assertThat(profile.name()).isEqualTo("local");
        assertThat(profile.project().name()).isEqualTo("Local project");
        assertThat(profile.repos()).hasSize(1);
        assertThat(profile.repo().id()).isEqualTo("main");

        assertThat(directory.find("local")).contains(doc);
    }

    @Test
    void missingProjectThrowsPdlcConfigException() {
        assertThatThrownBy(() -> directory.project("does-not-exist"))
                .isInstanceOf(PdlcConfigException.class)
                .hasMessageContaining("Missing project:");
    }

    @Test
    void agentsAndNotifyComeFromSameNamedYamlProfileElseDeploymentProfile() {
        directory.save("local", projectDoc("local", "Local project"), "admin@acme");
        Profile local = directory.project("local");
        assertThat(local.notifyConfig().provider()).isEqualTo("slack");
        assertThat(local.agents().gateway()).isEqualTo("http://local-gw");

        directory.save("other", projectDoc("other", "Other project"), "admin@acme");
        Profile other = directory.project("other");
        assertThat(other.notifyConfig().provider()).isEqualTo("email");
        assertThat(other.agents().gateway()).isEqualTo("http://deploy-gw");
    }

    private static ProjectDocument projectDoc(String id, String name) {
        return new ProjectDocument(
                new ProjectMeta(id, name, null, List.of(), "", new ProjectMeta.BuildDefaults("omp acp")),
                new BoardConfig("in-memory", "org", "proj", Map.of(), Map.of(), new BoardConfig.AuthConfig("none", "kv://none")),
                List.of(new RepoConfig("main", "local-git", "/tmp/r", "main", "openspec", List.of(), true)),
                Map.of(
                        "G1", new GateConfig(List.of("PO"), false),
                        "G2", new GateConfig(List.of("PO"), false),
                        "G3", new GateConfig(List.of("PO"), false),
                        "PLAN", new GateConfig(List.of("SquadLead"), false)));
    }
}
