package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkItemRepository extends CrudRepository<WorkItemEntity, UUID> {

    Optional<WorkItemEntity> findByProfileAndBoardId(String profile, String boardId);

    List<WorkItemEntity> findAllByOrderByUpdatedAtDesc();
}
