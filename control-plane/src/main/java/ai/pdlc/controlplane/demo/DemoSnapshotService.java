package ai.pdlc.controlplane.demo;

import ai.pdlc.controlplane.persistence.WorkItemEntity;
import ai.pdlc.controlplane.persistence.WorkItemRepository;
import ai.pdlc.controlplane.web.ConflictException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Snapshot read/guard surface shared by every controller: {@link #find}/{@link #findAllIndexed}
 * back the read-only DTO field and the gate/grill/spec-doc branching; {@link #requireWritable}
 * guards every mutation route (REST and webhook ingress) so a snapshot item can never be
 * commented on, approved, signed, or re-created by a replayed webhook.
 */
@Service
public class DemoSnapshotService {

    public static final String READ_ONLY_MESSAGE =
            "Demo snapshots are read-only; start the live restaurant demo instead";

    private final DemoSnapshotRepository snapshots;
    private final WorkItemRepository workItems;

    public DemoSnapshotService(DemoSnapshotRepository snapshots, WorkItemRepository workItems) {
        this.snapshots = snapshots;
        this.workItems = workItems;
    }

    public Optional<DemoSnapshotEntity> find(UUID workItemId) {
        return snapshots.findById(workItemId);
    }

    /** Loads every snapshot row once, indexed by work item id - list endpoints must not issue one
     * query per row. */
    public Map<UUID, DemoSnapshotEntity> findAllIndexed() {
        Map<UUID, DemoSnapshotEntity> index = new LinkedHashMap<>();
        snapshots.findAll().forEach(row -> index.put(row.workItemId(), row));
        return index;
    }

    public void requireWritable(UUID workItemId) {
        if (snapshots.existsById(workItemId)) {
            throw new ConflictException(READ_ONLY_MESSAGE);
        }
    }

    /** Webhook-ingress guard: a snapshot item is looked up by board id (profiles never mix), before
     * {@code BoardPort.onWebhook} is invoked - so a replayed/forged webhook targeting a snapshot's
     * board id never reaches the board, the webhook ledger, git, or a workflow. */
    public void requireWritable(String profile, String boardId) {
        Optional<WorkItemEntity> row = workItems.findByProfileAndBoardId(profile, boardId);
        if (row.isPresent()) {
            requireWritable(row.get().id());
        }
    }
}
