package ai.pdlc.controlplane.projects;

import ai.pdlc.core.config.PdlcConfig;
import ai.pdlc.core.config.ProjectDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Seeds every {@code pdlc.yaml} {@code profiles.*} entry into the {@code projects} table once, on
 * every control-plane startup, unconditionally (unlike {@code DemoInitializer}, which is gated by
 * {@code pdlc.demo.enabled}) — an Admin-managed project always needs a DB row to edit, even
 * outside the demo profile. {@code ON CONFLICT (id) DO NOTHING}: an existing row (whether seeded
 * earlier or since edited by an Admin) is never overwritten. Ordered before {@link
 * ai.pdlc.controlplane.demo.DemoInitializer}, which reads {@code activeProfile} (still resolved
 * from {@code pdlc.yaml} directly, not the DB) and does not depend on this seeder's rows, but
 * keeping a stable, documented startup order avoids surprises for anything added later that does.
 */
@Component
@Order(1)
public class ProjectSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProjectSeeder.class);

    private final DataSource dataSource;
    private final PdlcConfig pdlcConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProjectSeeder(DataSource dataSource, PdlcConfig pdlcConfig) {
        this.dataSource = dataSource;
        this.pdlcConfig = pdlcConfig;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        for (String id : pdlcConfig.profiles().keySet()) {
            seedOne(id);
        }
    }

    private void seedOne(String id) throws SQLException {
        long lockKey = (0x50524F4AL << 32) | (id.hashCode() & 0xFFFFFFFFL);
        try (Connection lockConn = dataSource.getConnection()) {
            acquireLock(lockConn, lockKey);
            try {
                ProjectDocument doc = pdlcConfig.document(id);
                String json;
                try {
                    json = mapper.writeValueAsString(doc);
                } catch (Exception e) {
                    throw new IllegalStateException("Could not serialize project " + id + " from pdlc.yaml", e);
                }
                int inserted;
                try (PreparedStatement ps = lockConn.prepareStatement("""
                        INSERT INTO projects (id, name, config_json, updated_by)
                        VALUES (?, ?, ?, 'pdlc.yaml')
                        ON CONFLICT (id) DO NOTHING
                        """)) {
                    ps.setString(1, id);
                    ps.setString(2, doc.project().name());
                    ps.setString(3, json);
                    inserted = ps.executeUpdate();
                }
                if (inserted > 0) {
                    log.info("[projects] seeded '{}' from pdlc.yaml", id);
                } else {
                    log.info("[projects] '{}' already present; leaving DB config untouched", id);
                }
            } finally {
                releaseLock(lockConn, lockKey);
            }
        }
    }

    private void acquireLock(Connection conn, long key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_lock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        }
    }

    private void releaseLock(Connection conn, long key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        }
    }
}
