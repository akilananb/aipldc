package ai.pdlc.controlplane.connections;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Persistence for connections, the model catalog and one-time imports; {@link JdbcConnectionStore} in production. */
public interface ConnectionStore {

    record ConnectionRow(String id, String scope, String workspaceId, String kind, String authType, String secretRef,
                         String baseUrl, String status, OffsetDateTime expiresAt, OffsetDateTime createdAt,
                         String createdBy, OffsetDateTime updatedAt, String updatedBy, OffsetDateTime revokedAt,
                         String revokedBy) {
    }

    record ModelRow(String id, String connectionId, String providerModel, String displayName, boolean enabled,
                    OffsetDateTime updatedAt, String updatedBy) {
    }

    Optional<ConnectionRow> connection(String id);

    List<ConnectionRow> connections();

    /** Returns false when the id is taken. */
    boolean insertConnection(ConnectionRow row);

    void updateConnection(String id, String secretRef, String baseUrl, OffsetDateTime expiresAt, String updatedBy);

    void revokeConnection(String id, String revokedBy);

    Optional<ModelRow> model(String id);

    List<ModelRow> models();

    /** Returns false when the id is taken. */
    boolean insertModel(ModelRow row);

    void updateModel(ModelRow row);

    /** Records a one-time import; returns false if it was already recorded. */
    boolean recordImport(String id, String details);

    /** Replaces the details of a recorded import (e.g. once its outcome is known). */
    void updateImport(String id, String details);
}
