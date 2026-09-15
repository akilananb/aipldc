package ai.pdlc.controlplane.demo;

import java.util.List;

/**
 * The story's gate review state: {@code stage} is {@code G1|G2|G3}; {@code version} is the gate
 * episode version ({@code ReviewState.version}) — the artifact version for G1 (2), the first G2
 * episode (1), and the first release-pack version (1) for G3. {@code approvals} is the frozen map
 * (keyed by role for G1/G2, by doc id for G3) flattened to a list. {@code openBlockingComments} is
 * the count of unresolved blocking comments at this gate.
 */
public record DemoGateFixture(
        String stage,
        int version,
        List<DemoApprovalFixture> approvals,
        int openBlockingComments) {
}
