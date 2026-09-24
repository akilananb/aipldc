package ai.pdlc.adapters.projects;

import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.config.PdlcConfigException;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.ProjectDocument;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ProjectDirectory} backed by the shared Postgres {@code projects} table — plain JDBC
 * (like {@code LocalMetricsAdapter}), not Spring Data JDBC, since {@code core}/{@code adapters}
 * carry no Spring dependency. Control-plane and agents are separate JVMs (tech-stack §6); each
 * holds its own cache with a short TTL rather than sharing invalidation across the process
 * boundary — a project edit's board/repo/gate change is visible everywhere within {@link
 * #CACHE_TTL}, and immediately in the editing process itself via {@link #invalidate}.
 */
public final class JdbcProjectDirectory implements ProjectDirectory {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final PdlcConfig deployment;
    private final String deploymentProfile;
    private final ObjectMapper mapper;
    private final Map<String, CachedProfile> cache = new ConcurrentHashMap<>();

    private record CachedProfile(Profile profile, Instant loadedAt) {
        boolean expired() {
            return Instant.now().isAfter(loadedAt.plus(CACHE_TTL));
        }
    }

    public JdbcProjectDirectory(DataSource dataSource, PdlcConfig deployment, String deploymentProfile, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.deployment = deployment;
        this.deploymentProfile = deploymentProfile;
        this.mapper = mapper;
    }

    @Override
    public Profile project(String id) {
        CachedProfile cached = cache.get(id);
        if (cached != null && !cached.expired()) {
            return cached.profile();
        }
        ProjectDocument doc = find(id).orElseThrow(() -> new PdlcConfigException("Missing project: " + id));
        Profile deploymentDefaults = deployment.profiles().containsKey(id)
                ? deployment.profile(id)
                : deployment.profile(deploymentProfile);
        Profile profile = new Profile(id, doc.project(), doc.board(), doc.repos(),
                deploymentDefaults.notifyConfig(), deploymentDefaults.agents(), doc.gates());
        cache.put(id, new CachedProfile(profile, Instant.now()));
        return profile;
    }

    @Override
    public Map<String, Profile> projects() {
        List<String> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM projects ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString("id"));
            }
        } catch (SQLException e) {
            throw new JdbcProjectDirectoryException("Could not list projects", e);
        }
        Map<String, Profile> result = new LinkedHashMap<>();
        for (String id : ids) {
            result.put(id, project(id));
        }
        return result;
    }

    @Override
    public Optional<ProjectDocument> find(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT config_json FROM projects WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapper.readValue(rs.getString("config_json"), ProjectDocument.class));
            }
        } catch (Exception e) {
            throw new JdbcProjectDirectoryException("Could not read project " + id, e);
        }
    }

    @Override
    public void save(String id, ProjectDocument doc, String updatedBy) {
        String name = doc.project() == null ? id : doc.project().name();
        String json;
        try {
            json = mapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw new JdbcProjectDirectoryException("Could not serialize project " + id, e);
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO projects (id, name, config_json, updated_by, updated_at)
                     VALUES (?, ?, ?, ?, now())
                     ON CONFLICT (id) DO UPDATE SET
                         name = EXCLUDED.name,
                         config_json = EXCLUDED.config_json,
                         updated_by = EXCLUDED.updated_by,
                         updated_at = now()
                     """)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, json);
            ps.setString(4, updatedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new JdbcProjectDirectoryException("Could not save project " + id, e);
        }
        invalidate(id);
    }

    @Override
    public boolean delete(String id) {
        int rows;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM projects WHERE id = ?")) {
            ps.setString(1, id);
            rows = ps.executeUpdate();
        } catch (SQLException e) {
            throw new JdbcProjectDirectoryException("Could not delete project " + id, e);
        }
        invalidate(id);
        return rows > 0;
    }

    @Override
    public void invalidate(String id) {
        cache.remove(id);
    }
}
