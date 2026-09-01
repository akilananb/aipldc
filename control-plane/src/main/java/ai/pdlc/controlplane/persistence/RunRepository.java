package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

public interface RunRepository extends CrudRepository<RunEntity, UUID> {

    List<RunEntity> findByWorkItemIdOrderByCreatedAtDesc(UUID workItemId);
}
