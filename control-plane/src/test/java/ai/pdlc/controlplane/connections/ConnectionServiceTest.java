package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.ConnectionDto;
import ai.pdlc.controlplane.web.dto.ConnectionRequest;
import ai.pdlc.controlplane.web.dto.ModelDto;
import ai.pdlc.controlplane.web.dto.ModelRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionServiceTest {

    static final Identity ADMIN = new Identity("it@acme", "Admin");
    static final Identity LEAD = new Identity("lead@acme", "SquadLead");

    private final InMemoryConnectionStore store = new InMemoryConnectionStore();
    private final ModelCatalog catalog = new ModelCatalog(store);
    private final ConnectionService service = new ConnectionService(store, catalog);

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
}
