package ai.pdlc.controlplane.platform;

import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The models a published agent may bind (configurable-agent-platform.md §4). Slice 1 derives the
 * catalog from the deployment profile's {@code agents.roles.*.model} entries in {@code pdlc.yaml} -
 * the same models the agents service already routes to. A later Phase 1 slice replaces this with
 * an enterprise-admin managed, DB-backed catalog tied to model provider connections.
 */
@Component
public class ModelCatalog {

    private final Set<String> models;

    public ModelCatalog(Profile deploymentProfile) {
        AgentsConfig agents = deploymentProfile.agents();
        Set<String> collected = new TreeSet<>();
        if (agents != null && agents.roles() != null) {
            agents.roles().values().stream()
                    .map(AgentsConfig.RoleConfig::model)
                    .filter(Objects::nonNull)
                    .forEach(collected::add);
        }
        this.models = Collections.unmodifiableSet(collected);
    }

    public Set<String> authorizedModels() {
        return models;
    }
}
