package ai.pdlc.controlplane.platform;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-process {@link DefinitionStore} for service tests (public: the runs tests reuse it). */
public class InMemoryDefinitionStore implements AgentRegistryStore, ToolRegistryStore {

    private final Map<String, DefinitionRow> agents = new HashMap<>();
    private final Map<String, List<VersionRow>> versions = new HashMap<>();

    private static String key(String ws, String id) {
        return ws + "/" + id;
    }

    @Override
    public Optional<DefinitionRow> find(String workspaceId, String id) {
        return Optional.ofNullable(agents.get(key(workspaceId, id)));
    }

    @Override
    public List<DefinitionRow> list(String workspaceId) {
        return agents.values().stream().filter(a -> a.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(DefinitionRow::id)).toList();
    }

    @Override
    public boolean insert(String workspaceId, String id, String name, String specJson, String createdBy) {
        OffsetDateTime now = OffsetDateTime.now();
        return agents.putIfAbsent(key(workspaceId, id), new DefinitionRow(workspaceId, id, name, specJson, 1,
                Status.ACTIVE, null, now, createdBy, now, createdBy)) == null;
    }

    @Override
    public boolean updateDraft(String workspaceId, String id, int expectedRevision, String name, String specJson,
                               String updatedBy) {
        DefinitionRow a = agents.get(key(workspaceId, id));
        if (a == null || a.draftRevision() != expectedRevision) {
            return false;
        }
        agents.put(key(workspaceId, id), new DefinitionRow(workspaceId, id, name, specJson, expectedRevision + 1,
                a.status(), a.currentVersion(), a.createdAt(), a.createdBy(), OffsetDateTime.now(), updatedBy));
        return true;
    }

    @Override
    public List<VersionRow> versions(String workspaceId, String definitionId) {
        return List.copyOf(versions.getOrDefault(key(workspaceId, definitionId), List.of()));
    }

    @Override
    public Optional<VersionRow> version(String workspaceId, String definitionId, int version) {
        return versions(workspaceId, definitionId).stream().filter(v -> v.version() == version).findFirst();
    }

    @Override
    public boolean insertVersion(VersionRow row) {
        List<VersionRow> list = versions.computeIfAbsent(key(row.workspaceId(), row.definitionId()), k -> new ArrayList<>());
        if (list.stream().anyMatch(v -> v.version() == row.version())) {
            return false;
        }
        list.add(new VersionRow(row.workspaceId(), row.definitionId(), row.version(), row.name(), row.specJson(),
                row.contentHash(), OffsetDateTime.now(), row.publishedBy()));
        return true;
    }

    @Override
    public void setCurrentVersion(String workspaceId, String id, int version, String updatedBy) {
        DefinitionRow a = agents.get(key(workspaceId, id));
        agents.put(key(workspaceId, id), new DefinitionRow(workspaceId, id, a.draftName(), a.draftSpecJson(),
                a.draftRevision(), a.status(), version, a.createdAt(), a.createdBy(), OffsetDateTime.now(), updatedBy));
    }

    @Override
    public void setStatus(String workspaceId, String id, Status status, String updatedBy) {
        DefinitionRow a = agents.get(key(workspaceId, id));
        agents.put(key(workspaceId, id), new DefinitionRow(workspaceId, id, a.draftName(), a.draftSpecJson(),
                a.draftRevision(), status, a.currentVersion(), a.createdAt(), a.createdBy(), OffsetDateTime.now(), updatedBy));
    }
}
