package ai.pdlc.controlplane.sandbox;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcSandboxImageStore implements SandboxImageStore {

    private static final RowMapper<ImageRow> IMAGE = (rs, n) -> new ImageRow(
            rs.getString("id"), rs.getString("image_ref"), rs.getString("description"), rs.getString("input_schema_json"),
            rs.getString("output_schema_json"), rs.getString("egress_hosts_json"), rs.getInt("cpu_millis"),
            rs.getInt("memory_mb"), rs.getInt("timeout_seconds"), rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"),
            rs.getObject("updated_at", OffsetDateTime.class), rs.getString("updated_by"),
            rs.getObject("retired_at", OffsetDateTime.class), rs.getString("retired_by"));

    private final JdbcTemplate jdbc;

    public JdbcSandboxImageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ImageRow> find(String id) {
        return jdbc.query("SELECT * FROM sandbox_images WHERE id = ?", IMAGE, id).stream().findFirst();
    }

    @Override
    public List<ImageRow> list() {
        return jdbc.query("SELECT * FROM sandbox_images ORDER BY id", IMAGE);
    }

    @Override
    public boolean insert(ImageRow r) {
        try {
            jdbc.update("""
                    INSERT INTO sandbox_images (id, image_ref, description, input_schema_json, output_schema_json,
                                                egress_hosts_json, cpu_millis, memory_mb, timeout_seconds, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    r.id(), r.imageRef(), r.description(), r.inputSchemaJson(), r.outputSchemaJson(), r.egressHostsJson(),
                    r.cpuMillis(), r.memoryMb(), r.timeoutSeconds(), r.createdBy(), r.createdBy());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void update(ImageRow r) {
        jdbc.update("""
                UPDATE sandbox_images SET image_ref = ?, description = ?, input_schema_json = ?, output_schema_json = ?,
                                          egress_hosts_json = ?, cpu_millis = ?, memory_mb = ?, timeout_seconds = ?,
                                          updated_at = now(), updated_by = ?
                WHERE id = ?""",
                r.imageRef(), r.description(), r.inputSchemaJson(), r.outputSchemaJson(), r.egressHostsJson(),
                r.cpuMillis(), r.memoryMb(), r.timeoutSeconds(), r.updatedBy(), r.id());
    }

    @Override
    public void retire(String id, String retiredBy) {
        jdbc.update("""
                UPDATE sandbox_images SET status = 'RETIRED', retired_at = now(), retired_by = ?, updated_at = now(), updated_by = ?
                WHERE id = ?""", retiredBy, retiredBy, id);
    }
}
