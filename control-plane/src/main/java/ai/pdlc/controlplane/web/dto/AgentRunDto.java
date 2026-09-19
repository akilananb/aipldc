package ai.pdlc.controlplane.web.dto;

import ai.pdlc.controlplane.persistence.RunEntity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentRunDto(UUID id, String agent, String phase, String status, String outcome,
                           OffsetDateTime startedAt, OffsetDateTime finishedAt, String traceUrl,
                           Long tokens, Integer iterations) {

    /** A 'running' row older than this is reported as abandoned: the agents worker died mid-call
     * and Temporal will retry as a fresh row. Matches AGENT_ACTIVITY_OPTIONS' StartToClose (10 min). */
    static final Duration RUNNING_TTL = Duration.ofMinutes(10);

    public static AgentRunDto from(RunEntity r, OffsetDateTime now) {
        boolean running = "running".equals(r.outcome());
        String status = running
                ? (r.createdAt().isAfter(now.minus(RUNNING_TTL)) ? "running" : "abandoned")
                : "finished";
        return new AgentRunDto(r.id(), r.agent(), r.phase(), status, r.outcome(), r.createdAt(), r.finishedAt(), r.traceUrl(), r.tokens(), r.iterations());
    }
}
