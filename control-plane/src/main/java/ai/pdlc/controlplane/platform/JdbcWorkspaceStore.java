package ai.pdlc.controlplane.platform;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

@Repository
public class JdbcWorkspaceStore implements WorkspaceStore {

    private static final RowMapper<WorkspaceRow> ROW = (rs, n) -> new WorkspaceRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("created_by"));

    private final JdbcTemplate jdbc;

    public JdbcWorkspaceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WorkspaceRow> find(String id) {
        return jdbc.query("SELECT * FROM workspaces WHERE id = ?", ROW, id).stream().findFirst();
    }

    @Override
    public List<WorkspaceRow> all() {
        return jdbc.query("SELECT * FROM workspaces ORDER BY id", ROW);
    }

    @Override
    public List<WorkspaceRow> forMember(String userId) {
        return jdbc.query("""
                SELECT * FROM workspaces w
                WHERE EXISTS (SELECT 1 FROM workspace_members m WHERE m.workspace_id = w.id AND m.user_id = ?)
                ORDER BY w.id""", ROW, userId);
    }

    @Override
    public boolean insert(String id, String name, String createdBy) {
        try {
            jdbc.update("INSERT INTO workspaces (id, name, created_by) VALUES (?, ?, ?)", id, name, createdBy);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Set<Capability> capabilities(String workspaceId, String userId) {
        Set<Capability> caps = EnumSet.noneOf(Capability.class);
        jdbc.query("SELECT capability FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                rs -> {
                    caps.add(Capability.valueOf(rs.getString("capability")));
                }, workspaceId, userId);
        return caps;
    }

    @Override
    public Map<String, Set<Capability>> members(String workspaceId) {
        Map<String, Set<Capability>> members = new TreeMap<>();
        jdbc.query("SELECT user_id, capability FROM workspace_members WHERE workspace_id = ?",
                rs -> {
                    members.computeIfAbsent(rs.getString("user_id"), u -> EnumSet.noneOf(Capability.class))
                            .add(Capability.valueOf(rs.getString("capability")));
                }, workspaceId);
        return members;
    }

    @Override
    public void setMember(String workspaceId, String userId, Set<Capability> capabilities, String grantedBy) {
        jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?", workspaceId, userId);
        for (Capability c : capabilities) {
            jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, capability, granted_by) VALUES (?, ?, ?, ?)",
                    workspaceId, userId, c.name(), grantedBy);
        }
    }
}
