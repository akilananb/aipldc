package ai.pdlc.controlplane.sandbox;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** In-memory {@link SandboxImageStore} for service tests (public: registry tests reuse it). */
public class InMemorySandboxImageStore implements SandboxImageStore {

    private final Map<String, ImageRow> rows = new TreeMap<>();

    @Override
    public Optional<ImageRow> find(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<ImageRow> list() {
        return new ArrayList<>(rows.values());
    }

    @Override
    public boolean insert(ImageRow r) {
        if (rows.containsKey(r.id())) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        rows.put(r.id(), new ImageRow(r.id(), r.imageRef(), r.description(), r.inputSchemaJson(), r.outputSchemaJson(),
                r.egressHostsJson(), r.cpuMillis(), r.memoryMb(), r.timeoutSeconds(), "ACTIVE", now, r.createdBy(), now,
                r.createdBy(), null, null));
        return true;
    }

    @Override
    public void update(ImageRow r) {
        ImageRow old = rows.get(r.id());
        rows.put(r.id(), new ImageRow(r.id(), r.imageRef(), r.description(), r.inputSchemaJson(), r.outputSchemaJson(),
                r.egressHostsJson(), r.cpuMillis(), r.memoryMb(), r.timeoutSeconds(), old.status(), old.createdAt(),
                old.createdBy(), OffsetDateTime.now(), r.updatedBy(), old.retiredAt(), old.retiredBy()));
    }

    @Override
    public void retire(String id, String retiredBy) {
        ImageRow o = rows.get(id);
        rows.put(id, new ImageRow(o.id(), o.imageRef(), o.description(), o.inputSchemaJson(), o.outputSchemaJson(),
                o.egressHostsJson(), o.cpuMillis(), o.memoryMb(), o.timeoutSeconds(), "RETIRED", o.createdAt(), o.createdBy(),
                OffsetDateTime.now(), retiredBy, OffsetDateTime.now(), retiredBy));
    }
}
