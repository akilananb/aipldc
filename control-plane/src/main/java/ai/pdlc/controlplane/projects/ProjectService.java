package ai.pdlc.controlplane.projects;

import ai.pdlc.controlplane.config.PortRegistry;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.ProjectDto;
import ai.pdlc.controlplane.web.dto.ProjectRequest;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.ProjectDocument;
import ai.pdlc.core.config.ProjectMeta;
import ai.pdlc.core.config.RepoConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Admin CRUD over DB-backed projects. Validation lives here (throwing {@link
 * IllegalArgumentException} with a {@code ;}-joined message, mapped to 400 by {@link
 * ai.pdlc.controlplane.web.ApiExceptionHandler}); the controller owns the HTTP surface and passes
 * the resolved {@link Identity} so this layer can enforce the Admin gate and record {@code
 * updatedBy}. {@link ProjectDirectory#save}/{@link ProjectDirectory#delete} persist the row; the
 * matching {@link PortRegistry} caches are invalidated immediately so a save is visible to every
 * adapter the next time it resolves a port for that project.
 */
@Service
public class ProjectService {

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    private static final Set<String> BOARD_PROVIDERS = Set.of("azure-devops", "local-jdbc", "in-memory");
    private static final Set<String> REPO_PROVIDERS = Set.of("github", "local-git", "in-memory");
    private static final List<String> REQUIRED_GATES = List.of("G1", "G2", "G3", "PLAN");
    private static final String WORK_ITEMS_COUNT_SQL = "SELECT count(*) FROM work_items WHERE profile = ?";

    private final ProjectDirectory projects;
    private final PortRegistry ports;
    private final JdbcTemplate jdbcTemplate;

    public ProjectService(ProjectDirectory projects, PortRegistry ports, JdbcTemplate jdbcTemplate) {
        this.projects = projects;
        this.ports = ports;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProjectDto> list() {
        Map<String, Row> rows = timestamps();
        return projects.projects().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    Profile p = e.getValue();
                    Row row = rows.get(e.getKey());
                    return toDto(e.getKey(), p.project(), p.board(), p.repos(), p.gates(), row);
                })
                .toList();
    }

    public ProjectDto get(String id) {
        ProjectDocument doc = projects.find(id).orElseThrow(() -> new NotFoundException("No project " + id));
        return toDto(id, doc.project(), doc.board(), doc.repos(), doc.gates(), timestamps(id));
    }

    public ProjectDto upsert(String id, ProjectRequest request, Identity identity) {
        requireAdmin(identity);
        validate(id, request);
        ProjectDocument doc = toDocument(id, request);
        projects.save(id, doc, identity.user());
        ports.invalidate(id);
        return toDto(id, doc.project(), doc.board(), doc.repos(), doc.gates(), timestamps(id));
    }

    public void delete(String id, Identity identity) {
        requireAdmin(identity);
        Integer count = jdbcTemplate.queryForObject(WORK_ITEMS_COUNT_SQL, Integer.class, id);
        if (count != null && count > 0) {
            throw new ConflictException("Project has work items");
        }
        projects.delete(id);
        ports.invalidate(id);
    }

    private void requireAdmin(Identity identity) {
        if (!"Admin".equals(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not an Admin");
        }
    }

    private void validate(String id, ProjectRequest request) {
        List<String> errors = new ArrayList<>();
        if (id == null || !ID.matcher(id).matches()) {
            errors.add("id must match " + ID.pattern());
        }
        if (blank(request.name())) {
            errors.add("name is required");
        }
        ProjectDto.BoardDto board = request.board();
        if (board == null) {
            errors.add("board is required");
        } else if (!BOARD_PROVIDERS.contains(board.provider())) {
            errors.add("board.provider must be one of " + BOARD_PROVIDERS);
        }
        List<ProjectDto.RepoDto> repos = request.repos();
        if (repos == null || repos.isEmpty()) {
            errors.add("at least one repo is required");
        } else {
            Set<String> repoIds = new HashSet<>();
            int primaryCount = 0;
            for (ProjectDto.RepoDto repo : repos) {
                if (repo.id() == null || !ID.matcher(repo.id()).matches()) {
                    errors.add("repo id must match " + ID.pattern());
                }
                if (repo.id() != null && !repoIds.add(repo.id())) {
                    errors.add("duplicate repo id \"" + repo.id() + "\"");
                }
                if (repo.primary()) {
                    primaryCount++;
                }
                if (!REPO_PROVIDERS.contains(repo.provider())) {
                    errors.add("repo \"" + repo.id() + "\" provider must be one of " + REPO_PROVIDERS);
                }
                if (blank(repo.url())) {
                    errors.add("repo \"" + repo.id() + "\" url is required");
                }
                if (blank(repo.defaultBranch())) {
                    errors.add("repo \"" + repo.id() + "\" defaultBranch is required");
                }
                if (blank(repo.specDir())) {
                    errors.add("repo \"" + repo.id() + "\" specDir is required");
                }
            }
            if (primaryCount != 1) {
                errors.add("exactly one repo must be primary");
            }
            Map<String, String> areaOwners = new HashMap<>();
            for (ProjectDto.RepoDto repo : repos) {
                if (repo.areas() != null) {
                    for (String area : repo.areas()) {
                        String owner = areaOwners.putIfAbsent(area, repo.id());
                        if (owner != null) {
                            errors.add("area \"" + area + "\" is served by more than one repo");
                        }
                    }
                }
            }
        }
        Map<String, ProjectDto.GateDto> gates = request.gates();
        if (gates == null) {
            errors.add("gates G1, G2, G3, PLAN are required");
        } else {
            for (String gate : REQUIRED_GATES) {
                ProjectDto.GateDto g = gates.get(gate);
                if (g == null || g.roles() == null || g.roles().isEmpty()) {
                    errors.add("gate " + gate + " must have at least one role");
                }
            }
        }
        if (!blank(request.confluenceUrl()) && !httpUrl(request.confluenceUrl())) {
            errors.add("confluenceUrl must start with http:// or https://");
        }
        if (request.docs() != null) {
            for (ProjectDto.DocLinkDto doc : request.docs()) {
                if (!blank(doc.url()) && !httpUrl(doc.url())) {
                    errors.add("doc url must start with http:// or https://");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private ProjectDocument toDocument(String id, ProjectRequest request) {
        List<ProjectMeta.DocLink> docs = request.docs() == null
                ? List.of()
                : request.docs().stream().map(d -> new ProjectMeta.DocLink(d.title(), d.url())).toList();
        ProjectMeta.BuildDefaults build = request.build() == null
                ? null
                : new ProjectMeta.BuildDefaults(request.build().acpAgent());
        ProjectMeta project = new ProjectMeta(id, request.name(), request.confluenceUrl(), docs, request.brief(), build);

        BoardConfig board = new BoardConfig(
                request.board().provider(),
                request.board().org(),
                request.board().project(),
                request.board().types(),
                request.board().states(),
                new BoardConfig.AuthConfig(request.board().authKind(), request.board().authSecretRef()));

        List<RepoConfig> repos = request.repos().stream()
                .map(r -> new RepoConfig(r.id(), r.provider(), r.url(), r.defaultBranch(), r.specDir(), r.areas(), r.primary()))
                .toList();

        Map<String, GateConfig> gates = request.gates().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new GateConfig(e.getValue().roles(), e.getValue().sod()),
                        (a, b) -> a,
                        LinkedHashMap::new));

        return new ProjectDocument(project, board, repos, gates);
    }

    private ProjectDto toDto(String id, ProjectMeta project, BoardConfig board, List<RepoConfig> repos,
                             Map<String, GateConfig> gates, Row row) {
        BoardConfig.AuthConfig auth = board.auth();
        return new ProjectDto(
                id,
                project.name(),
                new ProjectDto.BoardDto(
                        board.provider(),
                        board.org(),
                        board.project(),
                        auth == null ? null : auth.kind(),
                        auth == null ? null : auth.secretRef(),
                        board.types(),
                        board.states()),
                repos.stream()
                        .map(r -> new ProjectDto.RepoDto(r.id(), r.provider(), r.url(), r.defaultBranch(), r.specDir(), r.areas(), r.primary()))
                        .toList(),
                project.confluenceUrl(),
                project.docs().stream().map(d -> new ProjectDto.DocLinkDto(d.title(), d.url())).toList(),
                project.brief(),
                gates.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> new ProjectDto.GateDto(e.getValue().roles(), e.getValue().sod()),
                                (a, b) -> a,
                                LinkedHashMap::new)),
                new ProjectDto.BuildDto(project.build().acpAgent()),
                row == null ? null : row.updatedAt(),
                row == null ? null : row.updatedBy());
    }

    private Map<String, Row> timestamps() {
        Map<String, Row> rows = new HashMap<>();
        jdbcTemplate.query("SELECT id, updated_at, updated_by FROM projects", rs -> {
            rows.put(rs.getString("id"),
                    new Row(rs.getObject("updated_at", OffsetDateTime.class), rs.getString("updated_by")));
        });
        return rows;
    }

    private Row timestamps(String id) {
        List<Row> rows = jdbcTemplate.query(
                "SELECT updated_at, updated_by FROM projects WHERE id = ?",
                (rs, rowNum) -> new Row(rs.getObject("updated_at", OffsetDateTime.class), rs.getString("updated_by")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record Row(OffsetDateTime updatedAt, String updatedBy) {
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean httpUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }
}
