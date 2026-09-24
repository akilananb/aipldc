package ai.pdlc.controlplane.connections;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcConnectionStore implements ConnectionStore {

    private static final RowMapper<ConnectionRow> CONNECTION = (rs, n) -> new ConnectionRow(
            rs.getString("id"), rs.getString("scope"), rs.getString("workspace_id"), rs.getString("kind"),
            rs.getString("auth_type"), rs.getString("secret_ref"), rs.getString("base_url"), rs.getString("status"),
            rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("created_by"), rs.getObject("updated_at", OffsetDateTime.class), rs.getString("updated_by"),
            rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("revoked_by"));

    private static final RowMapper<ModelRow> MODEL = (rs, n) -> new ModelRow(
            rs.getString("id"), rs.getString("connection_id"), rs.getString("provider_model"),
            rs.getString("display_name"), rs.getBoolean("enabled"),
            rs.getObject("updated_at", OffsetDateTime.class), rs.getString("updated_by"));

    private final JdbcTemplate jdbc;

    public JdbcConnectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ConnectionRow> connection(String id) {
        return jdbc.query("SELECT * FROM connections WHERE id = ?", CONNECTION, id).stream().findFirst();
    }

    @Override
    public List<ConnectionRow> connections() {
        return jdbc.query("SELECT * FROM connections ORDER BY id", CONNECTION);
    }

    @Override
    public boolean insertConnection(ConnectionRow r) {
        try {
            jdbc.update("""
                    INSERT INTO connections (id, scope, workspace_id, kind, auth_type, secret_ref, base_url, expires_at,
                                             created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    r.id(), r.scope(), r.workspaceId(), r.kind(), r.authType(), r.secretRef(), r.baseUrl(),
                    r.expiresAt(), r.createdBy(), r.createdBy());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void updateConnection(String id, String secretRef, String baseUrl, OffsetDateTime expiresAt, String updatedBy) {
        jdbc.update("""
                UPDATE connections SET secret_ref = ?, base_url = ?, expires_at = ?, updated_at = now(), updated_by = ?
                WHERE id = ?""", secretRef, baseUrl, expiresAt, updatedBy, id);
    }

    @Override
    public void revokeConnection(String id, String revokedBy) {
        jdbc.update("""
                UPDATE connections SET status = 'REVOKED', revoked_at = now(), revoked_by = ?, updated_at = now(), updated_by = ?
                WHERE id = ?""", revokedBy, revokedBy, id);
    }

    @Override
    public Optional<ModelRow> model(String id) {
        return jdbc.query("SELECT * FROM models WHERE id = ?", MODEL, id).stream().findFirst();
    }

    @Override
    public List<ModelRow> models() {
        return jdbc.query("SELECT * FROM models ORDER BY id", MODEL);
    }

    @Override
    public boolean insertModel(ModelRow r) {
        try {
            jdbc.update("""
                    INSERT INTO models (id, connection_id, provider_model, display_name, enabled, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    r.id(), r.connectionId(), r.providerModel(), r.displayName(), r.enabled(), r.updatedBy(), r.updatedBy());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void updateModel(ModelRow r) {
        jdbc.update("""
                UPDATE models SET connection_id = ?, provider_model = ?, display_name = ?, enabled = ?,
                                  updated_at = now(), updated_by = ?
                WHERE id = ?""", r.connectionId(), r.providerModel(), r.displayName(), r.enabled(), r.updatedBy(), r.id());
    }

    @Override
    public boolean recordImport(String id, String details) {
        return jdbc.update("INSERT INTO platform_imports (id, details) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", id, details) == 1;
    }

    @Override
    public void updateImport(String id, String details) {
        jdbc.update("UPDATE platform_imports SET details = ? WHERE id = ?", details, id);
    }
}
