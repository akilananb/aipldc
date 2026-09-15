package ai.pdlc.controlplane.demo;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only on purpose: {@code demo_snapshots.work_item_id} is a pre-assigned (not
 * DB-generated) {@code @Id}, so a plain {@code CrudRepository.save(...)} would silently issue a
 * no-op UPDATE instead of an INSERT (Spring Data JDBC's default {@code isNew()} check treats any
 * non-null id as an existing aggregate). {@link ai.pdlc.controlplane.demo.DemoInitializer} inserts
 * rows via {@code JdbcAggregateTemplate.insert(...)} instead; this interface exposes only the
 * finder methods {@link DemoSnapshotService} actually needs, so that footgun cannot be reached
 * through this repository at all. */
public interface DemoSnapshotRepository extends Repository<DemoSnapshotEntity, UUID> {

    Optional<DemoSnapshotEntity> findById(UUID workItemId);

    boolean existsById(UUID workItemId);

    List<DemoSnapshotEntity> findAll();
}
