package ai.pdlc.controlplane.web.dto;

import java.time.Instant;
import java.util.UUID;

public record ItemDetailDto(
        UUID id,
        String profile,
        String boardId,
        String kind,
        String title,
        String description,
        String canonicalState,
        Integer latestVersion,
        String latestContentHash,
        ReviewStateDto gate,
        String parentId,
        String qualityVerdict,
        AgentRunDto activeRun,
        DemoSnapshotDto snapshot,
        AgentWorkDto agentWork) {

    /** Live plan/build status for a story item, so the UI can show what's happening during the
     * silent stretch between gate 1 approval and the plan gate ({@code planned}) reaching a
     * reviewer - {@code phase}: {@code reasoning} (the plan agent itself is between LLM/tool
     * calls, no build-worker involved), {@code waiting-for-worker} (a {@code pending} build_tasks
     * row has no claimant yet), {@code running} (a build-worker has claimed it). {@code kind}:
     * {@code plan} or {@code build}. {@code taskId}/{@code round}/{@code claimedBy}/{@code since}
     * are null for {@code reasoning} (no build_tasks row exists yet); {@code round} is only ever
     * set for a {@code plan} row. Null {@code agentWork} on the parent {@link ItemDetailDto} means
     * nothing is currently happening (or this isn't a story). */
    public record AgentWorkDto(String phase, String kind, String taskId, Integer round, String claimedBy,
                                Instant since, int workersOnline) {
    }
}
