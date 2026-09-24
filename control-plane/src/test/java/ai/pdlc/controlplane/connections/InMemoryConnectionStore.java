package ai.pdlc.controlplane.connections;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-process {@link ConnectionStore} for service tests (public so the platform registry tests can
 * back a real {@link ModelCatalog} with it); {@link JdbcConnectionStore} is covered by
 * {@code PlatformRegistryIntegrationTest}.
 */
public class InMemoryConnectionStore implements ConnectionStore {

    private final Map<String, ConnectionRow> connections = new TreeMap<>();
    private final Map<String, ModelRow> models = new TreeMap<>();
    private final Map<String, String> imports = new TreeMap<>();
    private final Set<String> grants = new java.util.TreeSet<>();

    /** An active API-key connection {@code connectionId} serving the given enabled models. */
    public static InMemoryConnectionStore withModels(String connectionId, String... modelIds) {
        InMemoryConnectionStore store = new InMemoryConnectionStore();
        store.insertConnection(new ConnectionRow(connectionId, "ENTERPRISE", null, "MODEL_PROVIDER", "API_KEY",
                "kv://llm-key", "https://gateway.example/v1", "ACTIVE", null, null, "it@acme", null, "it@acme", null, null));
        for (String id : modelIds) {
            store.insertModel(new ModelRow(id, connectionId, id, id, true, null, "it@acme"));
        }
        return store;
    }

    @Override
    public Optional<ConnectionRow> connection(String id) {
        return Optional.ofNullable(connections.get(id));
    }

    @Override
    public List<ConnectionRow> connections() {
        return List.copyOf(connections.values());
    }

    @Override
    public boolean insertConnection(ConnectionRow r) {
        OffsetDateTime now = OffsetDateTime.now();
        return connections.putIfAbsent(r.id(), new ConnectionRow(r.id(), r.scope(), r.workspaceId(), r.kind(), r.authType(),
                r.secretRef(), r.baseUrl(), "ACTIVE", r.expiresAt(), now, r.createdBy(), now, r.createdBy(), null, null,
                r.oauthClientId())) == null;
    }

    @Override
    public void updateConnection(String id, String secretRef, String baseUrl, OffsetDateTime expiresAt, String updatedBy) {
        ConnectionRow r = connections.get(id);
        connections.put(id, new ConnectionRow(id, r.scope(), r.workspaceId(), r.kind(), r.authType(), secretRef, baseUrl,
                r.status(), expiresAt, r.createdAt(), r.createdBy(), OffsetDateTime.now(), updatedBy, r.revokedAt(), r.revokedBy(),
                r.oauthClientId()));
    }

    @Override
    public void revokeConnection(String id, String revokedBy) {
        ConnectionRow r = connections.get(id);
        OffsetDateTime now = OffsetDateTime.now();
        connections.put(id, new ConnectionRow(id, r.scope(), r.workspaceId(), r.kind(), r.authType(), r.secretRef(),
                r.baseUrl(), "REVOKED", r.expiresAt(), r.createdAt(), r.createdBy(), now, revokedBy, now, revokedBy,
                r.oauthClientId()));
    }

    @Override
    public void setOAuthClientId(String id, String oauthClientId) {
        ConnectionRow r = connections.get(id);
        connections.put(id, new ConnectionRow(id, r.scope(), r.workspaceId(), r.kind(), r.authType(), r.secretRef(),
                r.baseUrl(), r.status(), r.expiresAt(), r.createdAt(), r.createdBy(), r.updatedAt(), r.updatedBy(),
                r.revokedAt(), r.revokedBy(), oauthClientId));
    }

    @Override
    public Optional<ModelRow> model(String id) {
        return Optional.ofNullable(models.get(id));
    }

    @Override
    public List<ModelRow> models() {
        return List.copyOf(models.values());
    }

    @Override
    public boolean insertModel(ModelRow r) {
        return models.putIfAbsent(r.id(), new ModelRow(r.id(), r.connectionId(), r.providerModel(), r.displayName(),
                r.enabled(), OffsetDateTime.now(), r.updatedBy())) == null;
    }

    @Override
    public void updateModel(ModelRow r) {
        models.put(r.id(), new ModelRow(r.id(), r.connectionId(), r.providerModel(), r.displayName(), r.enabled(),
                OffsetDateTime.now(), r.updatedBy()));
    }

    @Override
    public boolean recordImport(String id, String details) {
        return imports.putIfAbsent(id, details) == null;
    }

    @Override
    public void updateImport(String id, String details) {
        imports.put(id, details);
    }

    public Map<String, String> imports() {
        return imports;
    }

    @Override
    public boolean grant(String connectionId, String workspaceId, String grantedBy) {
        return grants.add(connectionId + "|" + workspaceId);
    }

    @Override
    public boolean revokeGrant(String connectionId, String workspaceId) {
        return grants.remove(connectionId + "|" + workspaceId);
    }

    @Override
    public boolean granted(String connectionId, String workspaceId) {
        return grants.contains(connectionId + "|" + workspaceId);
    }

    @Override
    public List<String> grantedWorkspaces(String connectionId) {
        return grants.stream().filter(g -> g.startsWith(connectionId + "|")).map(g -> g.substring(connectionId.length() + 1)).toList();
    }

    @Override
    public List<ConnectionRow> grantedTo(String workspaceId) {
        return grants.stream().filter(g -> g.endsWith("|" + workspaceId)).map(g -> connections.get(g.substring(0, g.indexOf('|'))))
                .toList();
    }
}
