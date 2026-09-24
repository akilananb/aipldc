package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.connections.ConnectionStore.ConnectionRow;
import ai.pdlc.controlplane.connections.ConnectionStore.ModelRow;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.core.platform.AgentSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    private final InMemoryConnectionStore store = InMemoryConnectionStore.withModels("gw", "sonnet", "haiku");
    private final ModelCatalog catalog = new ModelCatalog(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void enabledModelsOnActiveConnectionsAreAuthorized() {
        assertThat(catalog.authorizedModels()).containsExactly("haiku", "sonnet");
    }

    @Test
    void disablingAModelOrRevokingItsConnectionWithdrawsItImmediately() {
        store.updateModel(new ModelRow("haiku", "gw", "haiku", "Haiku", false, null, "it@acme"));
        assertThat(catalog.authorizedModels()).containsExactly("sonnet");

        store.revokeConnection("gw", "it@acme");
        assertThat(catalog.authorizedModels()).isEmpty();
        assertThat(catalog.list()).extracting(m -> m.unavailableReason())
                .containsExactly("disabled", "connection gw revoked");
    }

    @Test
    void anExpiredConnectionIsUnavailable() {
        store.updateConnection("gw", "kv://llm-key", "https://gateway.example/v1",
                OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC), "it@acme");

        assertThat(catalog.authorizedModels()).isEmpty();
        assertThat(catalog.list().get(0).unavailableReason()).startsWith("connection gw expired at");
    }

    @Test
    void resolvesTheBoundModelThenOnlyDeclaredFallbacks() {
        store.insertConnection(new ConnectionRow("other", "ENTERPRISE", null, "MODEL_PROVIDER", "NONE", null,
                "http://other/v1", "ACTIVE", null, null, "it@acme", null, "it@acme", null, null));
        store.insertModel(new ModelRow("local-llm", "other", "llama", "Local", true, null, "it@acme"));

        assertThat(catalog.resolve(new AgentSpec.ModelBinding("sonnet", List.of("local-llm"))))
                .isEqualTo(new ModelCatalog.ResolvedModel("sonnet", "sonnet", "gw", false));

        store.revokeConnection("gw", "it@acme");
        assertThat(catalog.resolve(new AgentSpec.ModelBinding("sonnet", List.of("haiku", "local-llm"))))
                .isEqualTo(new ModelCatalog.ResolvedModel("local-llm", "llama", "other", true));
    }

    @Test
    void noAvailableCandidateFailsWithEveryReasonInsteadOfADefault() {
        store.revokeConnection("gw", "it@acme");

        assertThatThrownBy(() -> catalog.resolve(new AgentSpec.ModelBinding("sonnet", List.of("gone"))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("No available model: sonnet (connection gw revoked); gone (not in the model catalog)");
    }
}
