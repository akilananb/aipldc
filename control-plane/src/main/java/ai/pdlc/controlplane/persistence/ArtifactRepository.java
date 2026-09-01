package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArtifactRepository extends CrudRepository<ArtifactEntity, UUID> {

    List<ArtifactEntity> findByWorkItemIdOrderByVersionDesc(UUID workItemId);

    Optional<ArtifactEntity> findByWorkItemIdAndVersion(UUID workItemId, int version);
}
