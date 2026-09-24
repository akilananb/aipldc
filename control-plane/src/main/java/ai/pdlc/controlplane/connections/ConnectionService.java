package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.connections.ConnectionStore.ConnectionRow;
import ai.pdlc.controlplane.connections.ConnectionStore.ModelRow;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.ConnectionDto;
import ai.pdlc.controlplane.web.dto.ConnectionRequest;
import ai.pdlc.controlplane.web.dto.ModelDto;
import ai.pdlc.controlplane.web.dto.ModelRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enterprise administration of model-provider connections and the model catalog
 * (docs/phase-1-execution-spec.md slice 3). Only the enterprise {@code Admin} role writes; any
 * signed-in user may list models (the agent editor needs them), only admins list connections.
 *
 * <p>Credentials are never stored or returned: a connection holds a {@code kv://name} secret
 * reference that only the execution adapter using it resolves. Control-plane deliberately does not
 * try to resolve it - the secret lives with the process that calls the provider. A revoked
 * connection is terminal; replace it with a new one rather than reviving it.
 */
@Service
public class ConnectionService {

    static final Pattern CONNECTION_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    static final Pattern MODEL_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/:-]{0,127}$");
    static final Pattern SECRET_REF = Pattern.compile("^kv://[a-z0-9][a-z0-9-]{0,63}$");
    static final Set<String> KINDS = Set.of("MODEL_PROVIDER");
    static final Set<String> AUTH_TYPES = Set.of("API_KEY", "NONE");

    private final ConnectionStore store;
    private final ModelCatalog catalog;

    public ConnectionService(ConnectionStore store, ModelCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    public List<ConnectionDto> connections(Identity identity) {
        requireEnterpriseAdmin(identity);
        return store.connections().stream().map(ConnectionService::toDto).toList();
    }

    @Transactional
    public ConnectionDto createConnection(ConnectionRequest request, Identity identity) {
        requireEnterpriseAdmin(identity);
        List<String> errors = new ArrayList<>();
        if (request.id() == null || !CONNECTION_ID.matcher(request.id()).matches()) {
            errors.add("id must match " + CONNECTION_ID.pattern());
        }
        if (!KINDS.contains(request.kind())) {
            errors.add("kind must be one of " + KINDS);
        }
        validateCredentials(request, errors);
        throwIfAny(errors);
        ConnectionRow row = new ConnectionRow(request.id(), "ENTERPRISE", null, request.kind(), request.authType(),
                blankToNull(request.secretRef()), request.baseUrl(), "ACTIVE", request.expiresAt(), null,
                identity.user(), null, identity.user(), null, null);
        if (!store.insertConnection(row)) {
            throw new ConflictException("Connection " + request.id() + " already exists");
        }
        return toDto(store.connection(request.id()).orElseThrow());
    }

    /** Rotates the secret reference, base URL or expiry of an active connection. */
    @Transactional
    public ConnectionDto updateConnection(String id, ConnectionRequest request, Identity identity) {
        requireEnterpriseAdmin(identity);
        ConnectionRow existing = requireActive(id);
        List<String> errors = new ArrayList<>();
        validateCredentials(new ConnectionRequest(id, existing.kind(), existing.authType(), request.secretRef(),
                request.baseUrl(), request.expiresAt()), errors);
        throwIfAny(errors);
        store.updateConnection(id, blankToNull(request.secretRef()), request.baseUrl(), request.expiresAt(), identity.user());
        return toDto(store.connection(id).orElseThrow());
    }

    @Transactional
    public ConnectionDto revokeConnection(String id, Identity identity) {
        requireEnterpriseAdmin(identity);
        requireActive(id);
        store.revokeConnection(id, identity.user());
        return toDto(store.connection(id).orElseThrow());
    }

    public List<ModelDto> models() {
        return catalog.list();
    }

    @Transactional
    public ModelDto createModel(ModelRequest request, Identity identity) {
        requireEnterpriseAdmin(identity);
        List<String> errors = new ArrayList<>();
        if (request.id() == null || !MODEL_ID.matcher(request.id()).matches()) {
            errors.add("id must match " + MODEL_ID.pattern());
        }
        validateModel(request, errors);
        throwIfAny(errors);
        requireActive(request.connectionId());
        ModelRow row = new ModelRow(request.id(), request.connectionId(), request.providerModel(),
                request.displayName(), request.enabled() == null || request.enabled(), null, identity.user());
        if (!store.insertModel(row)) {
            throw new ConflictException("Model " + request.id() + " already exists");
        }
        return model(request.id());
    }

    /** Edits a model; {@code enabled=false} withdraws it from new publications and runs immediately. */
    @Transactional
    public ModelDto updateModel(String id, ModelRequest request, Identity identity) {
        requireEnterpriseAdmin(identity);
        ModelRow existing = store.model(id).orElseThrow(() -> new NotFoundException("No model " + id));
        List<String> errors = new ArrayList<>();
        validateModel(request, errors);
        throwIfAny(errors);
        if (!request.connectionId().equals(existing.connectionId())) {
            requireActive(request.connectionId());
        }
        store.updateModel(new ModelRow(id, request.connectionId(), request.providerModel(), request.displayName(),
                request.enabled() == null ? existing.enabled() : request.enabled(), null, identity.user()));
        return model(id);
    }

    private ModelDto model(String id) {
        return catalog.list().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private ConnectionRow requireActive(String id) {
        ConnectionRow row = store.connection(id).orElseThrow(() -> new NotFoundException("No connection " + id));
        if (!"ACTIVE".equals(row.status())) {
            throw new ConflictException("Connection " + id + " is revoked");
        }
        return row;
    }

    private static void validateCredentials(ConnectionRequest request, List<String> errors) {
        if (!AUTH_TYPES.contains(request.authType())) {
            errors.add("authType must be one of " + AUTH_TYPES);
        } else if ("NONE".equals(request.authType())) {
            if (!blank(request.secretRef())) {
                errors.add("secretRef must be empty when authType is NONE");
            }
        } else if (request.secretRef() == null || !SECRET_REF.matcher(request.secretRef()).matches()) {
            errors.add("secretRef must be a secret reference matching " + SECRET_REF.pattern() + " - never a secret value");
        }
        if (request.baseUrl() == null || !(request.baseUrl().startsWith("https://") || request.baseUrl().startsWith("http://"))) {
            errors.add("baseUrl must start with http:// or https://");
        }
    }

    private static void validateModel(ModelRequest request, List<String> errors) {
        if (blank(request.connectionId())) {
            errors.add("connectionId is required");
        }
        if (blank(request.providerModel())) {
            errors.add("providerModel is required");
        }
        if (blank(request.displayName())) {
            errors.add("displayName is required");
        }
    }

    private static void throwIfAny(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static void requireEnterpriseAdmin(Identity identity) {
        if (!WorkspaceService.ENTERPRISE_ADMIN_ROLE.equals(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not an enterprise Admin");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s;
    }

    static ConnectionDto toDto(ConnectionRow r) {
        return new ConnectionDto(r.id(), r.scope(), r.workspaceId(), r.kind(), r.authType(), r.secretRef(), r.baseUrl(),
                r.status(), r.expiresAt(), r.createdAt(), r.createdBy(), r.updatedAt(), r.updatedBy(), r.revokedAt(),
                r.revokedBy());
    }
}
