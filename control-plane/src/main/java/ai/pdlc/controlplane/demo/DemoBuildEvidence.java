package ai.pdlc.controlplane.demo;

import java.util.Map;

/**
 * Recorded build evidence for stages 08b and later: the real source-08 implementation/tests as a
 * files map ({@code src/orders.ts}, {@code test/orders.test.ts}). {@code note} carries the explicit
 * "frozen moment after build, not an active worker" label — this is recorded evidence, not a live
 * build run.
 */
public record DemoBuildEvidence(
        String note,
        Map<String, String> files) {
}
