package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.temporal.AgentPresenceService;
import ai.pdlc.controlplane.web.dto.AgentsStatusDto;
import ai.pdlc.core.config.PdlcConfigException;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.RepoConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Read-only agent presence for the sidebar indicator and {@code /agents} page — see {@link
 * AgentPresenceService}. No {@code X-User}/{@code X-Role} requirement, same as {@code GET
 * /api/demo}. Per-project repo/build setup guidance now lives on {@code /projects} (Admin-editable
 * project config), not here. */
@RestController
@RequestMapping("/api/agents")
public class AgentsController {
    private final AgentPresenceService presence;
    private final ProjectDirectory projects;

    public AgentsController(AgentPresenceService presence, ProjectDirectory projects) {
        this.presence = presence;
        this.projects = projects;
    }

    @GetMapping
    public AgentsStatusDto agents() {
        Instant now = Instant.now();
        Map<String, AgentPresenceService.CurrentTask> currentAcpTasks = presence.currentAcpTasks();

        List<AgentsStatusDto.AgentPresenceDto> agents = presence.list().stream()
                .map(p -> toDto(p, now, currentAcpTasks))
                .sorted(Comparator
                        .comparing(AgentsStatusDto.AgentPresenceDto::online, Comparator.reverseOrder())
                        .thenComparing(AgentsStatusDto.AgentPresenceDto::kind)
                        .thenComparing(AgentsStatusDto.AgentPresenceDto::name))
                .toList();

        return new AgentsStatusDto(now, AgentPresenceService.ONLINE_WINDOW.toSeconds(), agents);
    }

    private AgentsStatusDto.AgentPresenceDto toDto(AgentPresenceService.Presence p, Instant now,
                                                     Map<String, AgentPresenceService.CurrentTask> currentAcpTasks) {
        boolean online = AgentPresenceService.onlineAt(p.lastSeenAt(), now);
        List<AgentsStatusDto.RepoDto> repos = p.repos().stream()
                .map(r -> new AgentsStatusDto.RepoDto(r.id(), r.mode(), r.location(), r.branch()))
                .toList();
        boolean repoMismatch = "acp".equals(p.kind()) && repos.stream().anyMatch(r -> mismatches(p.profile(), r));
        AgentsStatusDto.CurrentTaskDto current = "acp".equals(p.kind())
                ? toCurrentDto(currentAcpTasks.get(p.name())) : null;
        return new AgentsStatusDto.AgentPresenceDto(p.name(), p.kind(), p.profile(), online, p.firstSeenAt(),
                p.lastSeenAt(), p.acpAgent(), repos, repoMismatch, p.pollIntervalMs(), current);
    }

    /** "payload" mode always uses the project's own repo per task; only an override (local/remote
     * {@code TARGET_REPO_OVERRIDES}) can point somewhere else than a project repo. A mismatch is
     * an override whose id is not one of the project's repos, or whose location disagrees with
     * that repo's configured url — including when the project itself no longer exists. */
    private boolean mismatches(String profile, AgentsStatusDto.RepoDto repo) {
        if ("payload".equals(repo.mode())) {
            return false;
        }
        try {
            Profile project = projects.project(profile);
            RepoConfig repoConfig = project.repos().stream().filter(r -> r.id().equals(repo.id())).findFirst().orElse(null);
            return repoConfig == null || !repoConfig.url().equals(repo.location());
        } catch (PdlcConfigException missingProject) {
            return true;
        }
    }

    private AgentsStatusDto.CurrentTaskDto toCurrentDto(AgentPresenceService.CurrentTask t) {
        if (t == null) {
            return null;
        }
        return new AgentsStatusDto.CurrentTaskDto(
                t.claimId().toString(), t.kind(), t.storyBoardId(), t.taskId(), t.branch(), t.round(), t.since());
    }
}
