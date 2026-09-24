package ai.pdlc.controlplane.temporal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain unit tests for {@link AgentPresenceService}'s pure helpers — {@code currentTaskOf}
 * (payload parsing) and {@code onlineAt} (the shared 90s online window). No database, no
 * Temporal — see {@code BuildTaskAsyncCompletionTest} for the Testcontainers-backed integration
 * coverage of the claim/heartbeat boundary this service hangs off of. */
class AgentPresenceServiceTest {

    @Test
    void currentTaskOfParsesABuildPayload() {
        UUID claimId = UUID.randomUUID();
        Instant since = Instant.parse("2024-01-01T00:00:00Z");
        String payload = "{\"kind\":\"build\",\"story\":{\"profile\":\"local\",\"boardId\":\"4414\"},"
                + "\"task\":{\"id\":\"T2\"},\"branch\":\"story/4414\"}";

        AgentPresenceService.CurrentTask task = AgentPresenceService.currentTaskOf(claimId, payload, since);

        assertThat(task.kind()).isEqualTo("build");
        assertThat(task.storyBoardId()).isEqualTo("4414");
        assertThat(task.taskId()).isEqualTo("T2");
        assertThat(task.branch()).isEqualTo("story/4414");
        assertThat(task.round()).isNull();
        assertThat(task.since()).isEqualTo(since);
    }

    @Test
    void currentTaskOfParsesAPlanPayloadRound() {
        UUID claimId = UUID.randomUUID();
        Instant since = Instant.parse("2024-01-01T00:00:00Z");
        String payload = "{\"kind\":\"plan\",\"story\":{\"profile\":\"local\",\"boardId\":\"4414\"},"
                + "\"consultation\":{\"round\":2}}";

        AgentPresenceService.CurrentTask task = AgentPresenceService.currentTaskOf(claimId, payload, since);

        assertThat(task.kind()).isEqualTo("plan");
        assertThat(task.storyBoardId()).isEqualTo("4414");
        assertThat(task.taskId()).isEqualTo("plan");
        assertThat(task.branch()).isNull();
        assertThat(task.round()).isEqualTo(2);
    }

    @Test
    void currentTaskOfDegradesToUnknownOnUnparseableJson() {
        UUID claimId = UUID.randomUUID();
        Instant since = Instant.parse("2024-01-01T00:00:00Z");

        AgentPresenceService.CurrentTask task = AgentPresenceService.currentTaskOf(claimId, "not json", since);

        assertThat(task.kind()).isEqualTo("unknown");
        assertThat(task.storyBoardId()).isNull();
        assertThat(task.taskId()).isNull();
    }

    @Test
    void onlineAtIsTrueWithinTheNinetySecondWindow() {
        Instant now = Instant.parse("2024-01-01T00:02:00Z");

        assertThat(AgentPresenceService.onlineAt(now.minusSeconds(89), now)).isTrue();
        assertThat(AgentPresenceService.onlineAt(now.minusSeconds(91), now)).isFalse();
    }
}
