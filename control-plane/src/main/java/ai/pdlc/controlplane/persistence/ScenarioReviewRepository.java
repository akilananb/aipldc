package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScenarioReviewRepository extends CrudRepository<ScenarioReviewEntity, UUID> {

    List<ScenarioReviewEntity> findByArtifactIdOrderByAt(UUID artifactId);

    Optional<ScenarioReviewEntity> findByArtifactIdAndScenario(UUID artifactId, String scenario);
}
