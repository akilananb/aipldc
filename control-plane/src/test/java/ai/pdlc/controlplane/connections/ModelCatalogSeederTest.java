package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.connections.ConnectionStore.ModelRow;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.Profile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCatalogSeederTest {

    private final InMemoryConnectionStore store = new InMemoryConnectionStore();
    private final Profile profile = mock(Profile.class);

    ModelCatalogSeederTest() {
        Map<String, AgentsConfig.RoleConfig> roles = new LinkedHashMap<>();
        roles.put("grill", new AgentsConfig.RoleConfig("sonnet", null));
        roles.put("po", new AgentsConfig.RoleConfig("sonnet", 40_000L));
        roles.put("plan", new AgentsConfig.RoleConfig("opus", 40_000L));
        when(profile.agents()).thenReturn(new AgentsConfig("https://gateway.example/v1", null, roles));
    }

    @Test
    void importsTheGatewayAndEachDistinctRoleModelOnce() {
        new ModelCatalogSeeder(store, profile).seed();

        assertThat(store.connection("default-gateway")).get()
                .satisfies(c -> {
                    assertThat(c.baseUrl()).isEqualTo("https://gateway.example/v1");
                    assertThat(c.secretRef()).isEqualTo("kv://pdlc-llm-api-key");
                });
        assertThat(new ModelCatalog(store).authorizedModels()).containsExactly("opus", "sonnet");
    }

    @Test
    void neverReImportsOverAdminEdits() {
        ModelCatalogSeeder seeder = new ModelCatalogSeeder(store, profile);
        seeder.seed();
        store.updateModel(new ModelRow("opus", "default-gateway", "opus", "Opus", false, null, "it@acme"));

        seeder.seed();

        assertThat(store.model("opus")).get().extracting(ModelRow::enabled).isEqualTo(false);
    }
}
