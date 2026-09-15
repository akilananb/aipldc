package ai.pdlc.controlplane.demo;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Snapshot provenance/read-cache for one seeded work item (V10 {@code demo_snapshots}).
 * {@code gateJson}/{@code grillJson} are pre-serialized {@code ReviewStateDto}/{@code
 * GrillQuestionsDto} JSON computed once at seed time; {@code null} when the item's kind has no
 * gate/grill surface (task/release/bug/most features).
 */
@Table("demo_snapshots")
public record DemoSnapshotEntity(
        @Id UUID workItemId,
        String seedVersion,
        String snapshotKey,
        String label,
        int ordinal,
        String sourceRef,
        boolean replay,
        String gitRef,
        String gateJson,
        String grillJson) {
}
