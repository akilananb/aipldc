package ai.pdlc.controlplane.platform;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the AgentDefinition registry: one mutable draft per agent plus append-only
 * published versions. {@link JdbcAgentRegistryStore} in production.
 */
public interface AgentRegistryStore {

    enum Status { ACTIVE, RETIRED }

    record AgentRow(String workspaceId, String id, String draftName, String draftSpecJson, int draftRevision,
                    Status status, Integer currentVersion, OffsetDateTime createdAt, String createdBy,
                    OffsetDateTime updatedAt, String updatedBy) {
    }

    record VersionRow(String workspaceId, String agentId, int version, String name, String specJson,
                      String contentHash, OffsetDateTime publishedAt, String publishedBy) {
    }

    Optional<AgentRow> find(String workspaceId, String id);

    List<AgentRow> list(String workspaceId);

    /** Inserts at draft revision 1; returns false when the id is already taken in this workspace. */
    boolean insert(String workspaceId, String id, String name, String specJson, String createdBy);

    /**
     * Replaces the draft and bumps its revision, only if the stored revision is still
     * {@code expectedRevision}; returns false on a stale revision.
     */
    boolean updateDraft(String workspaceId, String id, int expectedRevision, String name, String specJson, String updatedBy);

    List<VersionRow> versions(String workspaceId, String agentId);

    Optional<VersionRow> version(String workspaceId, String agentId, int version);

    /** Returns false when that version number already exists (a concurrent publish won). */
    boolean insertVersion(VersionRow row);

    void setCurrentVersion(String workspaceId, String id, int version, String updatedBy);

    void setStatus(String workspaceId, String id, Status status, String updatedBy);
}
