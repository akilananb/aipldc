package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

public interface QualityReportRepository extends CrudRepository<QualityReportEntity, UUID> {

    List<QualityReportEntity> findByWorkItemIdOrderByCreatedAtDesc(UUID workItemId);
}
