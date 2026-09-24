package ai.pdlc.controlplane.sandbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Persistence for the sandbox image catalog ({@code sandbox_images}, V22). Schemas and hosts are stored as JSON text. */
public interface SandboxImageStore {

    record ImageRow(String id, String imageRef, String description, String inputSchemaJson, String outputSchemaJson,
                    String egressHostsJson, int cpuMillis, int memoryMb, int timeoutSeconds, String status,
                    OffsetDateTime createdAt, String createdBy, OffsetDateTime updatedAt, String updatedBy,
                    OffsetDateTime retiredAt, String retiredBy) {
    }

    Optional<ImageRow> find(String id);

    List<ImageRow> list();

    /** False when the id is taken. */
    boolean insert(ImageRow row);

    void update(ImageRow row);

    void retire(String id, String retiredBy);
}
