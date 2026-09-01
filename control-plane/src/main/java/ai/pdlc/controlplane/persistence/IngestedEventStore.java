package ai.pdlc.controlplane.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Idempotent webhook ingestion dedupe — orchestration-decision §5 "Event ingress dedupes by
 * item+rev". A composite natural key ({@code profile, item_id, rev}) doesn't map to Spring Data
 * JDBC's single-column {@code CrudRepository}, so this uses a plain {@code INSERT ... ON CONFLICT
 * DO NOTHING} and reports whether the row was newly inserted.
 */
@Repository
public class IngestedEventStore {

    private final JdbcTemplate jdbcTemplate;

    public IngestedEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Returns {@code true} if this is the first time {@code (profile, itemId, rev)} was seen. */
    public boolean recordIfNew(String profile, String itemId, long rev, String kind) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO ingested_events (profile, item_id, rev, kind) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (profile, item_id, rev) DO NOTHING",
                profile, itemId, rev, kind);
        return inserted > 0;
    }
}
