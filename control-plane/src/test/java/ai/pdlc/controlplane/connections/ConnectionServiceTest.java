package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.ConnectionDto;
import ai.pdlc.controlplane.web.dto.ConnectionRequest;
import ai.pdlc.controlplane.web.dto.ModelDto;
import ai.pdlc.controlplane.web.dto.ModelRequest;
import ai.pdlc.controlplane.platform.InMemoryWorkspaceStore;
import ai.pdlc.controlplane.platform.TestRegistries;
import ai.pdlc.controlplane.platform.WorkspaceService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionServiceTest {

    static final Identity ADMIN = new Identity("it@acme", "Admin");
    static final Identity LEAD = new Identity("lead@acme", "SquadLead");

    private final InMemoryConnectionStore store = new InMemoryConnectionStore();
    private final ModelCatalog catalog = new ModelCatalog(store);
    private final InMemoryWorkspaceStore workspaceStore = new InMemoryWorkspaceStore();
    private final WorkspaceService workspaces = new WorkspaceService(workspaceStore);
    private final ConnectionService service = new ConnectionService(store, catalog, workspaces,
            TestRegistries.egress(Set.of("internal.example")));

    private static ConnectionRequest openRouter() {
        return new ConnectionRequest("openrouter", "MODEL_PROVIDER", "API_KEY", "kv://openrouter-key",
                "https://openrouter.ai/api/v1", null);
    }

    @Test
    void onlyAnEnterpriseAdminManagesConnectionsAndModels() {
        assertThatThrownBy(() -> service.createConnection(openRouter(), LEAD)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.connections(LEAD)).isInstanceOf(ForbiddenException.class);
        service.createConnection(openRouter(), ADMIN);
        assertThatThrownBy(() -> service.createModel(new ModelRequest("m", "openrouter", "m", "M", null), LEAD))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aConnectionStoresOnlyASecretReferenceNeverASecretValue() {
        assertThatThrownBy(() -> service.createConnection(new ConnectionRequest("bad", "MODEL_PROVIDER", "API_KEY",
                "sk-live-1234567890", "https://api.example/v1", null), ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never a secret value");
        assertThatThrownBy(() -> service.createConnection(new ConnectionRequest("bad", "MODEL_PROVIDER", "NONE",
                "kv://x", "https://api.example/v1", null), ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be empty when authType is NONE");

        ConnectionDto created = service.createConnection(openRouter(), ADMIN);
        assertThat(created.secretRef()).isEqualTo("kv://openrouter-key");
        assertThat(created.status()).isEqualTo("ACTIVE");
    }

    @Test
    void validatesIdKindAndBaseUrl() {
        assertThatThrownBy(() -> service.createConnection(new ConnectionRequest("Bad Id", "TOOL", "API_KEY",
                "kv://k", "ftp://x", null), ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id must match")
                .hasMessageContaining("kind must be one of")
                .hasMessageContaining("baseUrl must start with");
        service.createConnection(openRouter(), ADMIN);
        assertThatThrownBy(() -> service.createConnection(openRouter(), ADMIN)).isInstanceOf(ConflictException.class);
    }

    @Test
    void revocationIsTerminalAndMakesItsModelsUnavailable() {
        service.createConnection(openRouter(), ADMIN);
        service.createModel(new ModelRequest("anthropic/claude-sonnet", "openrouter", "anthropic/claude-sonnet-4",
                "Claude Sonnet", null), ADMIN);

        ConnectionDto revoked = service.revokeConnection("openrouter", ADMIN);

        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedBy()).isEqualTo("it@acme");
        assertThat(service.models()).extracting(ModelDto::available).containsExactly(false);
        assertThatThrownBy(() -> service.revokeConnection("openrouter", ADMIN)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.updateConnection("openrouter", openRouter(), ADMIN)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.createModel(new ModelRequest("x", "openrouter", "x", "X", null), ADMIN))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void rotatingTheSecretReferenceKeepsTheConnection() {
        service.createConnection(openRouter(), ADMIN);

        ConnectionDto rotated = service.updateConnection("openrouter", new ConnectionRequest(null, null, null,
                "kv://openrouter-key-2", "https://openrouter.ai/api/v1", null), ADMIN);

        assertThat(rotated.secretRef()).isEqualTo("kv://openrouter-key-2");
        assertThat(rotated.authType()).isEqualTo("API_KEY");
    }

    @Test
    void modelsNeedAnExistingActiveConnectionAndCanBeDisabled() {
        assertThatThrownBy(() -> service.createModel(new ModelRequest("m", "missing", "m", "M", null), ADMIN))
                .isInstanceOf(NotFoundException.class);
        service.createConnection(openRouter(), ADMIN);
        ModelDto created = service.createModel(new ModelRequest("sonnet", "openrouter", "anthropic/claude-sonnet-4",
                "Sonnet", null), ADMIN);
        assertThat(created.enabled()).isTrue();
        assertThat(created.available()).isTrue();
        assertThatThrownBy(() -> service.createModel(new ModelRequest("sonnet", "openrouter", "x", "X", null), ADMIN))
                .isInstanceOf(ConflictException.class);

        ModelDto disabled = service.updateModel("sonnet", new ModelRequest(null, "openrouter", "anthropic/claude-sonnet-4",
                "Sonnet", false), ADMIN);

        assertThat(disabled.available()).isFalse();
        assertThat(disabled.unavailableReason()).isEqualTo("disabled");
        assertThat(catalog.authorizedModels()).isEmpty();
    }

    private static ConnectionRequest ordersApi(String baseUrl) {
        return new ConnectionRequest("orders-api", "HTTP_API", "API_KEY", "kv://orders-key", baseUrl, null);
    }

    @Test
    void anHttpApiConnectionMustTargetAnAllowedDestination() {
        assertThatThrownBy(() -> service.createConnection(ordersApi("http://169.254.169.254/latest"), ADMIN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("link-local / metadata");
        assertThatThrownBy(() -> service.createConnection(ordersApi("http://127.0.0.1:8080"), ADMIN))
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> service.createConnection(ordersApi("https://unknown.example"), ADMIN))
                .hasMessageContaining("does not resolve");
        assertThatThrownBy(() -> service.createConnection(ordersApi("https://api.example/v1?key=x"), ADMIN))
                .hasMessageContaining("query or fragment");

        assertThat(service.createConnection(ordersApi("https://internal.example/v1"), ADMIN).status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> service.updateConnection("orders-api", ordersApi("http://10.1.1.1"), ADMIN))
                .hasMessageContaining("private network");
    }

    @Test
    void modelProviderGatewaysAreNotSubjectToToolEgress() {
        service.createConnection(new ConnectionRequest("stub", "MODEL_PROVIDER", "NONE", null, "http://127.0.0.1:4000/v1", null), ADMIN);
    }

    @Test
    void grantsAreEnterpriseAdminOnlyAndOnlyForHttpApiConnections() {
        workspaces.create(new ai.pdlc.controlplane.web.dto.WorkspaceRequest("engineering", "Engineering", java.util.List.of("lead@acme")), ADMIN);
        service.createConnection(ordersApi("https://api.example/v1"), ADMIN);
        service.createConnection(openRouter(), ADMIN);

        assertThatThrownBy(() -> service.grant("orders-api", "engineering", LEAD)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.grant("openrouter", "engineering", ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.grant("orders-api", "nope", ADMIN)).isInstanceOf(NotFoundException.class);

        assertThat(service.grant("orders-api", "engineering", ADMIN)).containsExactly("engineering");
        assertThat(service.toolConnectionProblems("orders-api", "engineering")).isEmpty();
        assertThat(service.workspaceConnections("engineering", LEAD)).extracting(ConnectionDto::id).containsExactly("orders-api");
        assertThat(service.revokeGrant("orders-api", "engineering", ADMIN)).isEmpty();
        assertThatThrownBy(() -> service.revokeGrant("orders-api", "engineering", ADMIN)).isInstanceOf(NotFoundException.class);
        assertThat(service.toolConnectionProblems("orders-api", "engineering"))
                .containsExactly("connection orders-api is not granted to workspace engineering");
    }

    @Test
    void anExpiredConnectionIsNotUsableByTools() {
        workspaces.create(new ai.pdlc.controlplane.web.dto.WorkspaceRequest("engineering", "Engineering", java.util.List.of("lead@acme")), ADMIN);
        service.createConnection(new ConnectionRequest("orders-api", "HTTP_API", "NONE", null, "https://api.example/v1",
                java.time.OffsetDateTime.now().minusMinutes(1)), ADMIN);
        service.grant("orders-api", "engineering", ADMIN);

        assertThat(service.toolConnectionProblems("orders-api", "engineering")).singleElement()
                .asString().startsWith("connection orders-api expired at");
    }
}
