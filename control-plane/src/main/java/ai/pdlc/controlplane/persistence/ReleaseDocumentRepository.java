package ai.pdlc.controlplane.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseDocumentRepository extends CrudRepository<ReleaseDocumentEntity, UUID> {

    List<ReleaseDocumentEntity> findByStoryIdAndPackVersion(UUID storyId, int packVersion);

    Optional<ReleaseDocumentEntity> findByStoryIdAndDocIdAndPackVersion(UUID storyId, String docId, int packVersion);
}
