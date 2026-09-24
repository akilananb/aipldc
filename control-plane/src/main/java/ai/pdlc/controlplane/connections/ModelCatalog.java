package ai.pdlc.controlplane.connections;

import ai.pdlc.controlplane.connections.ConnectionStore.ConnectionRow;
import ai.pdlc.controlplane.connections.ConnectionStore.ModelRow;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.dto.ModelDto;
import ai.pdlc.core.platform.AgentSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The authorized model catalog (configurable-agent-platform.md §4, phase 1 slice 3). A model is
 * <em>available</em> when it is enabled and its provider connection is active and not expired.
 * Read from the database on every call, so an admin's change (new model, disabled model, revoked
 * connection) applies to the next publication or run without a restart.
 *
 * <p>There is no silent default model: {@link #resolve} tries the agent's bound model, then only
 * the fallbacks that agent declared, and otherwise fails naming why each candidate is unavailable.
 */
@Service
public class ModelCatalog {

    /** The model a run will call, and through which connection. */
    public record ResolvedModel(String model, String providerModel, String connectionId, boolean fallback) {
    }

    private final ConnectionStore store;
    private final Clock clock;

    @Autowired
    public ModelCatalog(ConnectionStore store) {
        this(store, Clock.systemUTC());
    }

    public ModelCatalog(ConnectionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Catalog ids an agent may bind at publication time. */
    public Set<String> authorizedModels() {
        Map<String, ConnectionRow> connections = connectionsById();
        return store.models().stream()
                .filter(m -> unavailableReason(m, connections) == null)
                .map(ModelRow::id)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public List<ModelDto> list() {
        Map<String, ConnectionRow> connections = connectionsById();
        return store.models().stream().map(m -> {
            String reason = unavailableReason(m, connections);
            return new ModelDto(m.id(), m.connectionId(), m.providerModel(), m.displayName(), m.enabled(),
                    reason == null, reason, m.updatedAt(), m.updatedBy());
        }).toList();
    }

    public ResolvedModel resolve(AgentSpec.ModelBinding binding) {
        Map<String, ConnectionRow> connections = connectionsById();
        List<String> candidates = new ArrayList<>();
        candidates.add(binding.model());
        if (binding.fallbacks() != null) {
            candidates.addAll(binding.fallbacks());
        }
        List<String> reasons = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String id = candidates.get(i);
            ModelRow model = store.model(id).orElse(null);
            String reason = model == null ? "not in the model catalog" : unavailableReason(model, connections);
            if (reason == null) {
                return new ResolvedModel(model.id(), model.providerModel(), model.connectionId(), i > 0);
            }
            reasons.add(id + " (" + reason + ")");
        }
        throw new ConflictException("No available model: " + String.join("; ", reasons));
    }

    String unavailableReason(ModelRow model, Map<String, ConnectionRow> connections) {
        if (!model.enabled()) {
            return "disabled";
        }
        ConnectionRow connection = connections.get(model.connectionId());
        if (connection == null) {
            return "connection " + model.connectionId() + " missing";
        }
        if (!"ACTIVE".equals(connection.status())) {
            return "connection " + connection.id() + " revoked";
        }
        if (connection.expiresAt() != null && !connection.expiresAt().toInstant().isAfter(clock.instant())) {
            return "connection " + connection.id() + " expired at " + connection.expiresAt();
        }
        return null;
    }

    private Map<String, ConnectionRow> connectionsById() {
        return store.connections().stream().collect(Collectors.toMap(ConnectionRow::id, Function.identity()));
    }
}
