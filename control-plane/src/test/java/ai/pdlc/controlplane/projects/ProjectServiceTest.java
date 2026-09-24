package ai.pdlc.controlplane.projects;

import ai.pdlc.controlplane.config.PortRegistry;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.dto.ProjectDto;
import ai.pdlc.controlplane.web.dto.ProjectRequest;
import ai.pdlc.core.config.ProjectDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link ProjectService} validation + delete guard, as a plain Mockito unit test (no Spring, no
 * database). */
class ProjectServiceTest {

    private static final Identity ADMIN = new Identity("admin@acme", "Admin");

    private final ProjectDirectory projects = mock(ProjectDirectory.class);
    private final PortRegistry ports = mock(PortRegistry.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ProjectService service = new ProjectService(projects, ports, jdbcTemplate);

    @Test
    void rejectsARequestMissingThePlanGate() {
        Map<String, ProjectDto.GateDto> gates = new HashMap<>(validRequest().gates());
        gates.remove("PLAN");

        assertThatThrownBy(() -> service.upsert("local", withGates(validRequest(), gates), ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLAN");
    }

    @Test
    void rejectsTwoReposBothMarkedPrimary() {
        List<ProjectDto.RepoDto> repos = List.of(
                new ProjectDto.RepoDto("api", "github", "https://github.com/acme/api", "main", "openspec", List.of(), true),
                new ProjectDto.RepoDto("web", "github", "https://github.com/acme/web", "main", "openspec", List.of(), true));

        assertThatThrownBy(() -> service.upsert("local", withRepos(validRequest(), repos), ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primary");
    }

    @Test
    void rejectsTwoReposDeclaringTheSameArea() {
        List<ProjectDto.RepoDto> repos = List.of(
                new ProjectDto.RepoDto("api", "github", "https://github.com/acme/api", "main", "openspec", List.of("orders"), true),
                new ProjectDto.RepoDto("web", "github", "https://github.com/acme/web", "main", "openspec", List.of("orders"), false));

        assertThatThrownBy(() -> service.upsert("local", withRepos(validRequest(), repos), ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orders");
    }

    @Test
    void rejectsAnInvalidId() {
        for (String badId : List.of("Local", "a".repeat(41), "-lead")) {
            assertThatThrownBy(() -> service.upsert(badId, validRequest(), ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id");
        }
    }

    @Test
    void deleteThrowsConflictWhenTheProjectHasWorkItems() {
        when(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM work_items WHERE profile = ?", Integer.class, "local")).thenReturn(1);

        assertThatThrownBy(() -> service.delete("local", ADMIN))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project has work items");
    }

    private static ProjectRequest validRequest() {
        return new ProjectRequest(
                "Local project",
                new ProjectDto.BoardDto("in-memory", "local", "PDLC", "none", "kv://none", Map.of(), Map.of()),
                List.of(new ProjectDto.RepoDto("main", "local-git", "/tmp/x", "main", "openspec", List.of(), true)),
                null,
                List.of(),
                "",
                Map.of(
                        "G1", new ProjectDto.GateDto(List.of("PO"), false),
                        "G2", new ProjectDto.GateDto(List.of("PO"), false),
                        "G3", new ProjectDto.GateDto(List.of("PO"), false),
                        "PLAN", new ProjectDto.GateDto(List.of("SquadLead"), false)),
                new ProjectDto.BuildDto("omp acp"));
    }

    private static ProjectRequest withRepos(ProjectRequest base, List<ProjectDto.RepoDto> repos) {
        return new ProjectRequest(base.name(), base.board(), repos, base.confluenceUrl(), base.docs(),
                base.brief(), base.gates(), base.build());
    }

    private static ProjectRequest withGates(ProjectRequest base, Map<String, ProjectDto.GateDto> gates) {
        return new ProjectRequest(base.name(), base.board(), base.repos(), base.confluenceUrl(), base.docs(),
                base.brief(), gates, base.build());
    }
}
