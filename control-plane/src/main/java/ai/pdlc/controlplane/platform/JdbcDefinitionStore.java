package ai.pdlc.controlplane.platform;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link DefinitionStore} over one kind's pair of tables: {@code <kind>_definitions} and
 * {@code <kind>_definition_versions} (whose owner column is {@code <kind>_id}). Table names come
 * from the subclass, never from input.
 */
abstract class JdbcDefinitionStore implements DefinitionStore {

    private static final RowMapper<DefinitionRow> DEFINITION = (rs, n) -> new DefinitionRow(
            rs.getString("workspace_id"),
            rs.getString("id"),
            rs.getString("draft_name"),
            rs.getString("draft_spec_json"),
            rs.getInt("draft_revision"),
            Status.valueOf(rs.getString("status")),
            (Integer) rs.getObject("current_version"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("created_by"),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getString("updated_by"));

    private final JdbcTemplate jdbc;
    private final String definitions;
    private final String versions;
    private final String owner;

    JdbcDefinitionStore(JdbcTemplate jdbc, String kind) {
        this.jdbc = jdbc;
        this.definitions = kind + "_definitions";
        this.versions = kind + "_definition_versions";
        this.owner = kind + "_id";
    }

    private VersionRow mapVersion(ResultSet rs, int n) throws SQLException {
        return new VersionRow(
                rs.getString("workspace_id"),
                rs.getString(owner),
                rs.getInt("version"),
                rs.getString("name"),
                rs.getString("spec_json"),
                rs.getString("content_hash"),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getString("published_by"));
    }

    @Override
    public Optional<DefinitionRow> find(String workspaceId, String id) {
        return jdbc.query("SELECT * FROM " + definitions + " WHERE workspace_id = ? AND id = ?", DEFINITION, workspaceId, id)
                .stream().findFirst();
    }

    @Override
    public List<DefinitionRow> list(String workspaceId) {
        return jdbc.query("SELECT * FROM " + definitions + " WHERE workspace_id = ? ORDER BY id", DEFINITION, workspaceId);
    }

    @Override
    public boolean insert(String workspaceId, String id, String name, String specJson, String createdBy) {
        try {
            jdbc.update("INSERT INTO " + definitions
                    + " (workspace_id, id, draft_name, draft_spec_json, draft_revision, created_by, updated_by)"
                    + " VALUES (?, ?, ?, ?, 1, ?, ?)", workspaceId, id, name, specJson, createdBy, createdBy);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean updateDraft(String workspaceId, String id, int expectedRevision, String name, String specJson,
                               String updatedBy) {
        return jdbc.update("UPDATE " + definitions
                + " SET draft_name = ?, draft_spec_json = ?, draft_revision = draft_revision + 1,"
                + " updated_at = now(), updated_by = ?"
                + " WHERE workspace_id = ? AND id = ? AND draft_revision = ?",
                name, specJson, updatedBy, workspaceId, id, expectedRevision) == 1;
    }

    @Override
    public List<VersionRow> versions(String workspaceId, String definitionId) {
        return jdbc.query("SELECT * FROM " + versions + " WHERE workspace_id = ? AND " + owner + " = ? ORDER BY version",
                this::mapVersion, workspaceId, definitionId);
    }

    @Override
    public Optional<VersionRow> version(String workspaceId, String definitionId, int version) {
        return jdbc.query("SELECT * FROM " + versions + " WHERE workspace_id = ? AND " + owner + " = ? AND version = ?",
                this::mapVersion, workspaceId, definitionId, version).stream().findFirst();
    }

    @Override
    public boolean insertVersion(VersionRow row) {
        try {
            jdbc.update("INSERT INTO " + versions
                    + " (workspace_id, " + owner + ", version, name, spec_json, content_hash, published_by)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    row.workspaceId(), row.definitionId(), row.version(), row.name(), row.specJson(),
                    row.contentHash(), row.publishedBy());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void setCurrentVersion(String workspaceId, String id, int version, String updatedBy) {
        jdbc.update("UPDATE " + definitions + " SET current_version = ?, updated_at = now(), updated_by = ? WHERE workspace_id = ? AND id = ?",
                version, updatedBy, workspaceId, id);
    }

    @Override
    public void setStatus(String workspaceId, String id, Status status, String updatedBy) {
        jdbc.update("UPDATE " + definitions + " SET status = ?, updated_at = now(), updated_by = ? WHERE workspace_id = ? AND id = ?",
                status.name(), updatedBy, workspaceId, id);
    }
}
