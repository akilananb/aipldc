package ai.pdlc.controlplane.web.dto;

import java.util.List;

/** {@code POST /api/build-tasks/claim} body — {@code filters.profile} is the "project code"
 * (see {@code WorkItemRef.profile}); {@code filters.story}/{@code filters.task} narrow further.
 * {@code presence} is optional — an older worker or {@code pdlc-assist} posts no presence, and
 * {@link ai.pdlc.controlplane.temporal.AgentPresenceService#touchAcpOnClaim} still upserts a bare
 * name/profile/last-seen row for it. */
public record ClaimRequest(String agent, ClaimFilters filters, Presence presence) {

    public record ClaimFilters(String profile, String story, String task) {
    }

    /** ACP_AGENT_CMD, poll interval, and one entry per {@code TARGET_REPO_OVERRIDES} repo (or a
     * single {@code payload}-mode entry when the worker has none) as the build-worker sees it
     * right now — see {@code build-worker/src/worker.ts#describePresence}. */
    public record Presence(String acpAgent, Integer pollIntervalMs, List<RepoPresence> repos) {
        public Presence {
            repos = repos == null ? List.of() : List.copyOf(repos);
        }
    }

    public record RepoPresence(String id, String mode, String location, String branch) {
    }
}
