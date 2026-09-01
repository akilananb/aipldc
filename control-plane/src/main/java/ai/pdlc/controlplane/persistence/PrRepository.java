package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface PrRepository extends CrudRepository<PrEntity, UUID> {

    Optional<PrEntity> findByWorkItemId(UUID workItemId);
}
