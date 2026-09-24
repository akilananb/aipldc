package ai.pdlc.agents.config;

import ai.pdlc.adapters.ado.AdoBoardAdapter;
import ai.pdlc.adapters.github.GitHubRepoAdapter;
import ai.pdlc.adapters.inmemory.InMemoryRepoAdapter;
import ai.pdlc.adapters.localgit.LocalGitRepoAdapter;
import ai.pdlc.adapters.remoteboard.RemoteBoardPort;
import ai.pdlc.adapters.serviceauth.ServiceCredentials;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.port.SecretsPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-project {@link BoardPort}/{@link RepoPort} resolution — mirrors {@code
 * ai.pdlc.controlplane.config.PortRegistry} exactly (tech-stack §2), replacing the old
 * process-startup-singleton {@code boardPort}/{@code repoPort} beans now that a project's
 * board/repo config is DB-backed and Admin-editable at runtime.
 */
@Component
public class PortRegistry {

    private final ProjectDirectory projects;
    private final SecretsPort secretsPort;
    private final String controlPlaneUrl;
    private final ServiceCredentials serviceCredentials;

    private final Map<String, BoardPort> boardCache = new ConcurrentHashMap<>();
    private final Map<String, RepoPort> repoCache = new ConcurrentHashMap<>();

    public PortRegistry(ProjectDirectory projects, SecretsPort secretsPort,
                         @Value("${pdlc.control-plane-url:http://control-plane:8081}") String controlPlaneUrl,
                         ServiceCredentials serviceCredentials) {
        this.projects = projects;
        this.secretsPort = secretsPort;
        this.controlPlaneUrl = controlPlaneUrl;
        this.serviceCredentials = serviceCredentials;
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

    public void invalidate(String projectId) {
        boardCache.remove(projectId);
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
            // The in-memory provider's board state lives only in control-plane's own JVM
            // (orchestration-decision §6: agents is a separate process); reading through its REST
            // API is how the grill/PO agents actually see real title/description/comments instead
            // of an empty local map. See RemoteBoardPort's javadoc for the bug this fixes.
            default -> new RemoteBoardPort(controlPlaneUrl, serviceCredentials);
        };
    }

    private RepoPort buildRepo(RepoConfig repo) {
        return switch (repo.provider()) {
            case "github" -> new GitHubRepoAdapter(repo.url(), secretsPort.resolve("kv://github-pat"));
            case "local-git" -> new LocalGitRepoAdapter(repo.url());
            default -> new InMemoryRepoAdapter();
        };
    }
}
