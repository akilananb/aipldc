package ai.pdlc.controlplane.config;

import ai.pdlc.adapters.ado.AdoBoardAdapter;
import ai.pdlc.adapters.github.GitHubRepoAdapter;
import ai.pdlc.adapters.inmemory.InMemoryBoardAdapter;
import ai.pdlc.adapters.inmemory.InMemoryRepoAdapter;
import ai.pdlc.adapters.localboard.LocalBoardAdapter;
import ai.pdlc.adapters.localci.LocalCiAdapter;
import ai.pdlc.adapters.localgit.LocalGitRepoAdapter;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.CiPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.port.SecretsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-project {@link BoardPort}/{@link RepoPort}/{@link CiPort} resolution — replaces the old
 * process-startup-singleton {@code boardPort}/{@code repoPort}/{@code ciPort} beans in {@link
 * AdapterBeans} now that a project's board/repo config is DB-backed and Admin-editable at runtime.
 * Same provider switch arms {@link AdapterBeans} used to host; cached per project (and per
 * project+repo) since building an adapter is cheap but not free, invalidated by {@link
 * ai.pdlc.controlplane.projects.ProjectService} whenever a project is saved/deleted.
 */
@Component
public class PortRegistry {

    private final ProjectDirectory projects;
    private final SecretsPort secretsPort;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    private final Map<String, BoardPort> boardCache = new ConcurrentHashMap<>();
    private final Map<String, RepoPort> repoCache = new ConcurrentHashMap<>();
    private final Map<String, CiPort> ciCache = new ConcurrentHashMap<>();

    public PortRegistry(ProjectDirectory projects, SecretsPort secretsPort, JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.projects = projects;
        this.secretsPort = secretsPort;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    public BoardPort board(String projectId) {
        return boardCache.computeIfAbsent(projectId, id -> buildBoard(projects.project(id)));
    }

    public RepoPort repo(String projectId, String repoId) {
        return repoCache.computeIfAbsent(projectId + ":" + repoId,
                key -> buildRepo(projects.project(projectId).repo(repoId)));
    }

    public RepoPort primaryRepo(String projectId) {
        Profile project = projects.project(projectId);
        return repo(projectId, project.repo().id());
    }

    public CiPort ci(String projectId) {
        return ciCache.computeIfAbsent(projectId, id -> new LocalCiAdapter(projects.project(id).repo().url()));
    }

    /** Drops every cached port for {@code projectId} so the next resolution re-reads the project's
     * current board/repo config. */
    public void invalidate(String projectId) {
        boardCache.remove(projectId);
        ciCache.remove(projectId);
        repoCache.keySet().removeIf(key -> key.startsWith(projectId + ":"));
    }

    private BoardPort buildBoard(Profile project) {
        return switch (project.board().provider()) {
            case "azure-devops" -> new AdoBoardAdapter(
                    project.board().org(),
                    project.board().project(),
                    secretsPort.resolve(project.board().auth().secretRef()),
                    project.board().states(),
                    project.board().types());
            case "local-jdbc" -> new LocalBoardAdapter(dataSource);
            default -> new InMemoryBoardAdapter(AdapterBeans.nextBoardIdSequenceStart(jdbcTemplate));
        };
    }

    private RepoPort buildRepo(RepoConfig repo) {
        // pdlc.yaml's consumed repo.* subset (tech-stack §4) has no `auth` key (unlike board.auth);
        // the pilot resolves GitHub's PAT by convention: env GITHUB_PAT via kv://github-pat.
        return switch (repo.provider()) {
            case "github" -> new GitHubRepoAdapter(repo.url(), secretsPort.resolve("kv://github-pat"));
            case "local-git" -> new LocalGitRepoAdapter(repo.url());
            default -> new InMemoryRepoAdapter();
        };
    }
}
