package ai.pdlc.controlplane.demo;

import java.time.Instant;

/**
 * An approval fixture mirroring {@code ai.pdlc.core.domain.Approval} without the {@code contentHash}
 * (computed at seed time from actual content). Exactly one of {@code artifactVersion} (G1/G2 role
 * approvals, always artifact version 2 in this bundle) or {@code docId} (G3 document signatures) is
 * non-null. {@code at} is the ISO-8601 instant the approval was recorded.
 */
public record DemoApprovalFixture(
        String who,
        String role,
        Integer artifactVersion,
        String docId,
        Instant at) {
}
