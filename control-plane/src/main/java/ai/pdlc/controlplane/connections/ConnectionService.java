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
import ai.pdlc.controlplane.web.dto.ConnectionTls;
import ai.pdlc.controlplane.web.dto.ModelDto;
import ai.pdlc.controlplane.web.dto.ModelRequest;
import ai.pdlc.core.platform.EgressPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
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
 *
 * <p>{@code HTTP_API} connections (Phase 2 slice 2.1) back workspace tools. Their base URL must pass
 * the {@link EgressPolicy} when saved (the executor checks again on every call), and a workspace can
 * use one only while the enterprise Admin's grant stands - revoking the grant or the connection
 * denies the next tool call of a running agent.
 */
@Service
public class ConnectionService {

    static final Pattern CONNECTION_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    static final Pattern MODEL_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/:-]{0,127}$");
    static final Pattern SECRET_REF = Pattern.compile("^kv://[a-z0-9][a-z0-9-]{0,63}$");
    public static final String MODEL_PROVIDER = "MODEL_PROVIDER";
    public static final String HTTP_API = "HTTP_API";
    public static final String MCP_SERVER = "MCP_SERVER";
    /** A remote A2A agent (slice 2.5): its origin, and credentials an a2a platform agent delegates with. */
    public static final String A2A_AGENT = "A2A_AGENT";
    /** An existing HTTP agent service a rest agent calls through a declared mapping (slice 2.6). */
    public static final String REST_AGENT = "REST_AGENT";
    /**
     * An existing gRPC agent service a grpc agent calls (slice 2.6b): {@code grpcs://host:port}, or
     * {@code grpc://host:port} (plaintext) only without credentials; optional pinned CA and mTLS client
     * certificate by reference. No OAuth: there is no discovery to find an authorization server.
     */
    public static final String GRPC_AGENT = "GRPC_AGENT";
    public static final String OAUTH_CLIENT_CREDENTIALS = "OAUTH_CLIENT_CREDENTIALS";
    static final Set<String> KINDS = Set.of(MODEL_PROVIDER, HTTP_API, MCP_SERVER, A2A_AGENT, REST_AGENT, GRPC_AGENT);
    /**
     * Kinds whose base URL workspace tools or agents call, so it must pass the egress policy and can be
     * granted to workspaces.
     */
    static final Set<String> TOOL_KINDS = Set.of(HTTP_API, MCP_SERVER, A2A_AGENT, REST_AGENT, GRPC_AGENT);
    /** Kinds that may authenticate with OAuth client credentials discovered from the server. */
    static final Set<String> OAUTH_KINDS = Set.of(MCP_SERVER, A2A_AGENT, REST_AGENT);
    static final Set<String> AUTH_TYPES = Set.of("API_KEY", "NONE", OAUTH_CLIENT_CREDENTIALS);
    /** {@code grpcs://host:port} or {@code grpc://host:port}: an authority and nothing else. */
    static final Pattern GRPC_TARGET = Pattern.compile("^grpcs?://(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+):([0-9]{1,5})$");
    static final Pattern OAUTH_CLIENT_ID = Pattern.compile("^[A-Za-z0-9._:@/-]{1,200}$");

    private final ConnectionStore store;
    private final ModelCatalog catalog;
    private final WorkspaceService workspaces;
    private final EgressPolicy egress;

    public ConnectionService(ConnectionStore store, ModelCatalog catalog, WorkspaceService workspaces, EgressPolicy egress) {
        this.store = store;
        this.catalog = catalog;
        this.workspaces = workspaces;
        this.egress = egress;
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
        checkEgress(request.kind(), request.baseUrl(), errors);
        throwIfAny(errors);
        ConnectionRow row = new ConnectionRow(request.id(), "ENTERPRISE", null, request.kind(), request.authType(),
                blankToNull(request.secretRef()), request.baseUrl(), "ACTIVE", request.expiresAt(), null,
                identity.user(), null, identity.user(), null, null, blankToNull(request.oauthClientId()),
                request.tls() == null || request.tls().empty() ? null : trimmed(request.tls()));
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
        String clientId = OAUTH_CLIENT_CREDENTIALS.equals(existing.authType()) && blank(request.oauthClientId())
                ? existing.oauthClientId() : request.oauthClientId();
        ConnectionTls tls = request.tls() == null ? existing.tls() : request.tls().empty() ? null : trimmed(request.tls());
        validateCredentials(new ConnectionRequest(id, existing.kind(), existing.authType(), request.secretRef(),
                request.baseUrl(), request.expiresAt(), clientId, tls), errors);
        checkEgress(existing.kind(), request.baseUrl(), errors);
        throwIfAny(errors);
        store.updateConnection(id, blankToNull(request.secretRef()), request.baseUrl(), request.expiresAt(), identity.user());
        store.setOAuthClientId(id, blankToNull(clientId));
        store.setTls(id, tls);
        return toDto(store.connection(id).orElseThrow());
    }

    @Transactional
    public ConnectionDto revokeConnection(String id, Identity identity) {
        requireEnterpriseAdmin(identity);
        requireActive(id);
        store.revokeConnection(id, identity.user());
        return toDto(store.connection(id).orElseThrow());
    }

    /** Lets the workspace's tools use an active HTTP_API connection (enterprise Admin). */
    @Transactional
    public List<String> grant(String id, String workspaceId, Identity identity) {
        requireEnterpriseAdmin(identity);
        ConnectionRow row = requireActive(id);
        if (!TOOL_KINDS.contains(row.kind())) {
            throw new IllegalArgumentException("Only " + TOOL_KINDS + " connections are granted to workspaces");
        }
        if (!workspaces.exists(workspaceId)) {
            throw new NotFoundException("No workspace " + workspaceId);
        }
        store.grant(id, workspaceId, identity.user());
        return store.grantedWorkspaces(id);
    }

    /** Takes effect on the next tool call: the executor re-checks the grant before every call. */
    @Transactional
    public List<String> revokeGrant(String id, String workspaceId, Identity identity) {
        requireEnterpriseAdmin(identity);
        store.connection(id).orElseThrow(() -> new NotFoundException("No connection " + id));
        if (!store.revokeGrant(id, workspaceId)) {
            throw new NotFoundException("Connection " + id + " is not granted to " + workspaceId);
        }
        return store.grantedWorkspaces(id);
    }

    public List<String> grants(String id, Identity identity) {
        requireEnterpriseAdmin(identity);
        store.connection(id).orElseThrow(() -> new NotFoundException("No connection " + id));
        return store.grantedWorkspaces(id);
    }

    /** The HTTP_API, MCP_SERVER and A2A_AGENT connections a workspace's authors may bind (members only; no secret refs). */
    public List<ConnectionDto> workspaceConnections(String workspaceId, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return store.grantedTo(workspaceId).stream()
                .map(r -> new ConnectionDto(r.id(), r.scope(), r.workspaceId(), r.kind(), r.authType(), null, r.baseUrl(),
                        r.status(), r.expiresAt(), r.createdAt(), r.createdBy(), r.updatedAt(), r.updatedBy(),
                        r.revokedAt(), r.revokedBy(), r.oauthClientId(), null))
                .toList();
    }

    /**
     * Why a workspace's tool cannot use {@code connectionId} right now (empty = usable): it must be
     * an active, unexpired connection of {@code requiredKind} (HTTP_API for http tools, MCP_SERVER
     * for mcp tools, A2A_AGENT for a2a agents, REST_AGENT for rest agents) granted to the workspace. Used at tool publication, at MCP discovery, and
     * whenever an agent pinning the tool is published or started.
     */
    public List<String> toolConnectionProblems(String connectionId, String workspaceId, String requiredKind) {
        ConnectionRow row = store.connection(connectionId).orElse(null);
        if (row == null) {
            return List.of("connection " + connectionId + " does not exist");
        }
        List<String> problems = new ArrayList<>();
        if (!requiredKind.equals(row.kind())) {
            problems.add("connection " + connectionId + " is not an " + requiredKind + " connection");
        }
        if (!"ACTIVE".equals(row.status())) {
            problems.add("connection " + connectionId + " is revoked");
        } else if (row.expiresAt() != null && !row.expiresAt().isAfter(OffsetDateTime.now())) {
            problems.add("connection " + connectionId + " expired at " + row.expiresAt());
        }
        if (!store.granted(connectionId, workspaceId)) {
            problems.add("connection " + connectionId + " is not granted to workspace " + workspaceId);
        }
        return problems;
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
        boolean oauth = OAUTH_CLIENT_CREDENTIALS.equals(request.authType());
        if (oauth && !OAUTH_KINDS.contains(request.kind())) {
            errors.add("authType " + OAUTH_CLIENT_CREDENTIALS + " is only supported for " + MCP_SERVER + ", " + A2A_AGENT
                    + " and " + REST_AGENT + " connections");
        }
        if (oauth && (request.oauthClientId() == null || !OAUTH_CLIENT_ID.matcher(request.oauthClientId()).matches())) {
            errors.add("oauthClientId must match " + OAUTH_CLIENT_ID.pattern());
        }
        if (!oauth && !blank(request.oauthClientId())) {
            errors.add("oauthClientId applies only to authType " + OAUTH_CLIENT_CREDENTIALS);
        }
        if (!AUTH_TYPES.contains(request.authType())) {
            errors.add("authType must be one of " + AUTH_TYPES);
        } else if ("NONE".equals(request.authType())) {
            if (!blank(request.secretRef())) {
                errors.add("secretRef must be empty when authType is NONE");
            }
        } else if (request.secretRef() == null || !SECRET_REF.matcher(request.secretRef()).matches()) {
            errors.add("secretRef must be a secret reference matching " + SECRET_REF.pattern() + " - never a secret value");
        }
        if (GRPC_AGENT.equals(request.kind())) {
            validateGrpcTarget(request, errors);
        } else {
            if (request.baseUrl() == null || !(request.baseUrl().startsWith("https://") || request.baseUrl().startsWith("http://"))) {
                errors.add("baseUrl must start with http:// or https://");
            }
            if (request.tls() != null && !request.tls().empty()) {
                errors.add("tls applies only to " + GRPC_AGENT + " connections");
            }
        }
    }

    /**
     * A gRPC target and its TLS references. Plaintext ({@code grpc://}) carries no credential and no TLS
     * material, so a bearer or client key is never sent where anyone on the path could read it.
     */
    private static void validateGrpcTarget(ConnectionRequest request, List<String> errors) {
        java.util.regex.Matcher m = request.baseUrl() == null ? null : GRPC_TARGET.matcher(request.baseUrl());
        if (m == null || !m.matches() || Integer.parseInt(m.group(2)) < 1 || Integer.parseInt(m.group(2)) > 65_535) {
            errors.add("baseUrl must be grpcs://host:port (or grpc://host:port without credentials)");
            return;
        }
        ConnectionTls tls = request.tls();
        boolean plaintext = request.baseUrl().startsWith("grpc://");
        if (plaintext && !"NONE".equals(request.authType())) {
            errors.add("a grpc:// (plaintext) connection cannot carry credentials; use grpcs://");
        }
        if (plaintext && tls != null && !tls.empty()) {
            errors.add("a grpc:// (plaintext) connection has no TLS settings; use grpcs://");
        }
        if (tls == null) {
            return;
        }
        for (String ref : java.util.Arrays.asList(tls.caRef(), tls.clientCertRef(), tls.clientKeyRef())) {
            if (!blank(ref) && !SECRET_REF.matcher(ref.strip()).matches()) {
                errors.add("tls references must match " + SECRET_REF.pattern() + " - never PEM content");
                break;
            }
        }
        if (blank(tls.clientCertRef()) != blank(tls.clientKeyRef())) {
            errors.add("tls.clientCertRef and tls.clientKeyRef go together (mTLS needs both)");
        }
    }

    private static ConnectionTls trimmed(ConnectionTls tls) {
        return new ConnectionTls(blankToNull(tls.caRef()), blankToNull(tls.clientCertRef()), blankToNull(tls.clientKeyRef()));
    }

    private void checkEgress(String kind, String baseUrl, List<String> errors) {
        if (!TOOL_KINDS.contains(kind) || baseUrl == null || !errors.isEmpty()) {
            return;
        }
        URI uri;
        try {
            // A gRPC target is checked as the https origin with the same host and port.
            uri = URI.create(baseUrl.startsWith("grpcs://") || baseUrl.startsWith("grpc://")
                    ? "https://" + baseUrl.substring(baseUrl.indexOf("://") + 3) : baseUrl);
        } catch (IllegalArgumentException e) {
            errors.add("baseUrl is not a valid URL");
            return;
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            errors.add("baseUrl must not have a query or fragment");
            return;
        }
        EgressPolicy.Decision decision = egress.check(uri);
        if (!decision.allowed()) {
            errors.add("baseUrl is not an allowed destination: " + decision.reason());
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
                r.revokedBy(), r.oauthClientId(), r.tls());
    }
}
