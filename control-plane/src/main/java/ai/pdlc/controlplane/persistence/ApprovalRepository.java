package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalRepository extends CrudRepository<ApprovalEntity, UUID> {

    List<ApprovalEntity> findByArtifactIdAndVersion(UUID artifactId, int version);
}
