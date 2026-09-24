package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.connections.ConnectionStore.ConnectionRow;
import ai.pdlc.controlplane.connections.ConnectionStore.ModelRow;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One-time import of the deployment's {@code pdlc.yaml} model routing into the DB-backed catalog
 * (docs/phase-1-execution-spec.md slice 3): a {@value #CONNECTION_ID} connection for
 * {@code agents.gateway} (API key resolved by the agents process from {@code kv://pdlc-llm-api-key},
 * i.e. {@code PDLC_LLM_API_KEY}) and one catalog model per distinct {@code agents.roles.*.model}.
 * Recorded in {@code platform_imports}, so it runs exactly once - later {@code pdlc.yaml} edits
 * never overwrite what an admin changed in the catalog.
 */
@Component
@Order(2)
public class ModelCatalogSeeder implements ApplicationRunner {

    static final String IMPORT_ID = "model-catalog-from-pdlc-yaml";
    static final String CONNECTION_ID = "default-gateway";
    static final String SEEDER = "system:pdlc.yaml";

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogSeeder.class);

    private final ConnectionStore store;
    private final Profile deploymentProfile;

    public ModelCatalogSeeder(ConnectionStore store, Profile deploymentProfile) {
        this.store = store;
        this.deploymentProfile = deploymentProfile;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed();
    }

    @Transactional
    public void seed() {
        AgentsConfig agents = deploymentProfile.agents();
        Set<String> models = new TreeSet<>();
        if (agents != null && agents.roles() != null) {
            agents.roles().values().stream().map(AgentsConfig.RoleConfig::model).filter(Objects::nonNull).forEach(models::add);
        }
        String gateway = agents == null ? null : agents.gateway();
        String details = "gateway=" + gateway + " models=" + models;
        if (!store.recordImport(IMPORT_ID, details)) {
            return;
        }
        if (gateway == null || gateway.isBlank()) {
            log.warn("pdlc.yaml has no agents.gateway; model catalog starts empty");
            return;
        }
        store.insertConnection(new ConnectionRow(CONNECTION_ID, "ENTERPRISE", null, "MODEL_PROVIDER", "API_KEY",
                "kv://pdlc-llm-api-key", gateway, "ACTIVE", null, null, SEEDER, null, SEEDER, null, null));
        for (String model : models) {
            store.insertModel(new ModelRow(model, CONNECTION_ID, model, model, true, null, SEEDER));
        }
        log.info("Seeded model catalog from pdlc.yaml: connection {} -> {}, models {}", CONNECTION_ID, gateway, models);
    }
}
