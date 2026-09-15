package ai.pdlc.controlplane.demo;

import java.util.List;

/**
 * Root of the curated restaurant-demo fixture bundle ({@code demo/restaurant-demo.json}).
 *
 * <p><b>Curated-snapshot provenance:</b> the real restaurant-service history contains seven story
 * revisions (v1..v7) before gate 1 passed. This bundle condenses them into exactly two curated
 * artifact versions per story: <b>v1 = the initial draft</b> (checkpoint {@code 06-agent-intake})
 * and <b>v2 = the final revision</b> (checkpoint {@code 07-spec-gate-g1}). No snapshot reflects a
 * partially-approved gate as fully passed — every {@code awaiting-*} story shows only the partial
 * approval set listed in its snapshot table.
 *
 * <p><b>Serialization convention:</b> empty collections are {@code []} (never omitted); absent
 * scalar fields ({@code parentBoardId}, {@code specChangePath}, {@code gate}, {@code buildEvidence},
 * nullable approval {@code artifactVersion}/{@code docId}, nullable comment
 * {@code resolvedInVersion}/agent fields) are serialized as an explicit JSON {@code null}. Records
 * rely on Jackson's default property-name matching (camelCase component names equal JSON keys), the
 * same convention the {@code web/dto} records use.
 */
public record DemoManifest(
        String version,
        String baselineSha,
        DemoLiveSeed live,
        List<DemoSnapshotGroup> snapshots) {
}
