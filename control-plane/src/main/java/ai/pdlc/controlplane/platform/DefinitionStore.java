package ai.pdlc.controlplane.platform;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for a versioned definition registry (agents, tools): one mutable draft per
 * definition plus append-only published versions. Each kind has its own tables and store bean
 * ({@link AgentRegistryStore}, {@link ToolRegistryStore}); {@link JdbcDefinitionStore} in production.
 */
public interface DefinitionStore {

    enum Status { ACTIVE, RETIRED }

    record DefinitionRow(String workspaceId, String id, String draftName, String draftSpecJson, int draftRevision,
                    Status status, Integer currentVersion, OffsetDateTime createdAt, String createdBy,
                    OffsetDateTime updatedAt, String updatedBy) {
    }

    record VersionRow(String workspaceId, String definitionId, int version, String name, String specJson,
                      String contentHash, OffsetDateTime publishedAt, String publishedBy) {
    }

    Optional<DefinitionRow> find(String workspaceId, String id);

    List<DefinitionRow> list(String workspaceId);

    /** Inserts at draft revision 1; returns false when the id is already taken in this workspace. */
    boolean insert(String workspaceId, String id, String name, String specJson, String createdBy);

    /**
     * Replaces the draft and bumps its revision, only if the stored revision is still
     * {@code expectedRevision}; returns false on a stale revision.
     */
    boolean updateDraft(String workspaceId, String id, int expectedRevision, String name, String specJson, String updatedBy);

    List<VersionRow> versions(String workspaceId, String definitionId);

    Optional<VersionRow> version(String workspaceId, String definitionId, int version);

    /** Returns false when that version number already exists (a concurrent publish won). */
    boolean insertVersion(VersionRow row);

    void setCurrentVersion(String workspaceId, String id, int version, String updatedBy);

    void setStatus(String workspaceId, String id, Status status, String updatedBy);
}
