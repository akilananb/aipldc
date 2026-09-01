package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewEventRepository extends CrudRepository<ReviewEventEntity, UUID> {

    List<ReviewEventEntity> findByWorkItemIdOrderByTs(UUID workItemId);
}
