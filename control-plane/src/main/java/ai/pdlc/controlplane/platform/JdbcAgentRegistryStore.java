package ai.pdlc.controlplane.platform;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAgentRegistryStore implements AgentRegistryStore {

    private static final RowMapper<AgentRow> AGENT = (rs, n) -> new AgentRow(
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

    private static final RowMapper<VersionRow> VERSION = (rs, n) -> new VersionRow(
            rs.getString("workspace_id"),
            rs.getString("agent_id"),
            rs.getInt("version"),
            rs.getString("name"),
            rs.getString("spec_json"),
            rs.getString("content_hash"),
            rs.getObject("published_at", OffsetDateTime.class),
            rs.getString("published_by"));

    private final JdbcTemplate jdbc;

    public JdbcAgentRegistryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AgentRow> find(String workspaceId, String id) {
        return jdbc.query("SELECT * FROM agent_definitions WHERE workspace_id = ? AND id = ?", AGENT, workspaceId, id)
                .stream().findFirst();
    }

    @Override
    public List<AgentRow> list(String workspaceId) {
        return jdbc.query("SELECT * FROM agent_definitions WHERE workspace_id = ? ORDER BY id", AGENT, workspaceId);
    }

    @Override
    public boolean insert(String workspaceId, String id, String name, String specJson, String createdBy) {
        try {
            jdbc.update("""
                    INSERT INTO agent_definitions
                        (workspace_id, id, draft_name, draft_spec_json, draft_revision, created_by, updated_by)
                    VALUES (?, ?, ?, ?, 1, ?, ?)""", workspaceId, id, name, specJson, createdBy, createdBy);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean updateDraft(String workspaceId, String id, int expectedRevision, String name, String specJson,
                               String updatedBy) {
        return jdbc.update("""
                UPDATE agent_definitions
                SET draft_name = ?, draft_spec_json = ?, draft_revision = draft_revision + 1,
                    updated_at = now(), updated_by = ?
                WHERE workspace_id = ? AND id = ? AND draft_revision = ?""",
                name, specJson, updatedBy, workspaceId, id, expectedRevision) == 1;
    }

    @Override
    public List<VersionRow> versions(String workspaceId, String agentId) {
        return jdbc.query("SELECT * FROM agent_definition_versions WHERE workspace_id = ? AND agent_id = ? ORDER BY version",
                VERSION, workspaceId, agentId);
    }

    @Override
    public Optional<VersionRow> version(String workspaceId, String agentId, int version) {
        return jdbc.query("SELECT * FROM agent_definition_versions WHERE workspace_id = ? AND agent_id = ? AND version = ?",
                VERSION, workspaceId, agentId, version).stream().findFirst();
    }

    @Override
    public boolean insertVersion(VersionRow row) {
        try {
            jdbc.update("""
                    INSERT INTO agent_definition_versions
                        (workspace_id, agent_id, version, name, spec_json, content_hash, published_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    row.workspaceId(), row.agentId(), row.version(), row.name(), row.specJson(),
                    row.contentHash(), row.publishedBy());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void setCurrentVersion(String workspaceId, String id, int version, String updatedBy) {
        jdbc.update("UPDATE agent_definitions SET current_version = ?, updated_at = now(), updated_by = ? WHERE workspace_id = ? AND id = ?",
                version, updatedBy, workspaceId, id);
    }

    @Override
    public void setStatus(String workspaceId, String id, Status status, String updatedBy) {
        jdbc.update("UPDATE agent_definitions SET status = ?, updated_at = now(), updated_by = ? WHERE workspace_id = ? AND id = ?",
                status.name(), updatedBy, workspaceId, id);
    }
}
