package ai.pdlc.controlplane.platform;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** In-process {@link WorkspaceStore} for service tests; {@link JdbcWorkspaceStore} is covered by
 * {@link PlatformRegistryIntegrationTest}. */
class InMemoryWorkspaceStore implements WorkspaceStore {

    private final Map<String, WorkspaceRow> workspaces = new TreeMap<>();
    private final Map<String, Map<String, Set<Capability>>> members = new TreeMap<>();

    @Override
    public Optional<WorkspaceRow> find(String id) {
        return Optional.ofNullable(workspaces.get(id));
    }

    @Override
    public List<WorkspaceRow> all() {
        return List.copyOf(workspaces.values());
    }

    @Override
    public List<WorkspaceRow> forMember(String userId) {
        return workspaces.values().stream().filter(w -> !capabilities(w.id(), userId).isEmpty()).toList();
    }

    @Override
    public boolean insert(String id, String name, String createdBy) {
        return workspaces.putIfAbsent(id, new WorkspaceRow(id, name, OffsetDateTime.now(), createdBy)) == null;
    }

    @Override
    public Set<Capability> capabilities(String workspaceId, String userId) {
        Set<Capability> caps = members.getOrDefault(workspaceId, Map.of()).get(userId);
        return caps == null ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(caps);
    }

    @Override
    public Map<String, Set<Capability>> members(String workspaceId) {
        return new TreeMap<>(members.getOrDefault(workspaceId, Map.of()));
    }

    @Override
    public void setMember(String workspaceId, String userId, Set<Capability> capabilities, String grantedBy) {
        Map<String, Set<Capability>> ws = members.computeIfAbsent(workspaceId, k -> new TreeMap<>());
        if (capabilities.isEmpty()) {
            ws.remove(userId);
        } else {
            ws.put(userId, EnumSet.copyOf(capabilities));
        }
    }
}
