package ai.pdlc.controlplane.platform;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Persistence for workspaces and their members; {@link JdbcWorkspaceStore} in production. */
public interface WorkspaceStore {

    record WorkspaceRow(String id, String name, OffsetDateTime createdAt, String createdBy) {
    }

    Optional<WorkspaceRow> find(String id);

    List<WorkspaceRow> all();

    List<WorkspaceRow> forMember(String userId);

    /** Returns false when a workspace with this id already exists. */
    boolean insert(String id, String name, String createdBy);

    Set<Capability> capabilities(String workspaceId, String userId);

    Map<String, Set<Capability>> members(String workspaceId);

    /** Replaces the user's capabilities in the workspace (an empty set removes the member). */
    void setMember(String workspaceId, String userId, Set<Capability> capabilities, String grantedBy);

    record LinkedProject(String id, String name) {
    }

    /** Links a PDLC project to the workspace; a no-op if already linked. */
    void linkProject(String workspaceId, String projectId, String linkedBy);

    List<LinkedProject> projects(String workspaceId);
}
