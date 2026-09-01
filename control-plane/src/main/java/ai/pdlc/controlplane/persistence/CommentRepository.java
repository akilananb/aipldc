package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

public interface CommentRepository extends CrudRepository<CommentEntity, UUID> {

    List<CommentEntity> findByArtifactIdOrderByCreatedAt(UUID artifactId);

    List<CommentEntity> findByArtifactIdAndBlockingTrueAndResolvedInVersionIsNull(UUID artifactId);
}
