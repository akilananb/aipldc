package ai.pdlc.controlplane.demo;

import java.util.List;

/**
 * One of the eight curated snapshot groups (stage {@code order} 1..8). {@code sourceRef} is
 * {@code <checkpoint-name>@<sha>}. {@code replay} is {@code true} only for the stage-11 group,
 * whose deploy/monitor content is explicitly replay evidence rather than live workflow output;
 * groups 06a..10 show no deploy/monitor activity, so their {@code replay} is {@code false}.
 */
public record DemoSnapshotGroup(
        String key,
        String label,
        int order,
        String sourceRef,
        boolean replay,
        List<DemoWorkItemFixture> items) {
}
