package ai.pdlc.controlplane.web.dto;

import java.time.Instant;
import java.util.List;

/** Body of {@code GET /api/agents} — every agent process the control plane has ever seen (ACP
 * build-workers and reasoning workers), online or offline, plus what each is currently working
 * on. {@code onlineWindowSeconds} mirrors {@code AgentPresenceService.ONLINE_WINDOW} so the UI
 * never has to hardcode it. */
public record AgentsStatusDto(Instant generatedAt, long onlineWindowSeconds, List<AgentPresenceDto> agents) {

    public record AgentPresenceDto(String name, String kind, String profile, boolean online, Instant firstSeenAt,
                                    Instant lastSeenAt, String acpAgent, List<RepoDto> repos, boolean repoMismatch,
                                    Integer pollIntervalMs, CurrentTaskDto current) {
    }

    public record RepoDto(String id, String mode, String location, String branch) {
    }

    public record CurrentTaskDto(String claimId, String kind, String storyBoardId, String taskId, String branch,
                                  Integer round, Instant since) {
    }
}
