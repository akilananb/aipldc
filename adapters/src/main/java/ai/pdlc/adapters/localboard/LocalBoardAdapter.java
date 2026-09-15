package ai.pdlc.adapters.localboard;

import ai.pdlc.core.domain.AttachmentRef;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.CommentRef;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link BoardPort} backed by the shared Postgres {@code local_board_*} tables - the
 * {@code local-jdbc} provider (plan step 2). Persisted, not in-process: control-plane and agents
 * share only the database, and the {@code local} profile previously used the volatile
 * {@code InMemoryBoardAdapter}, which lost every item/comment on restart. This adapter keeps that
 * adapter's exact behavior (missing-item errors, create defaults, case-insensitive title search,
 * the {@code updateFields} allowlist, transitions, comment defaults, parent-child semantics) but
 * with the state durably behind plain JDBC.
 *
 * <p>{@link #onWebhook} both normalizes the raw event AND (since there is no real board to be the
 * source of truth) applies the event's own content to the store - the local webhook payload IS the
 * board write. It records each normalized event in {@code local_board_webhooks}, a per-item
 * revision ledger separate from control-plane's {@code ingested_events}, so a replayed payload
 * never re-applies a mutation.
 */
public final class LocalBoardAdapter implements BoardPort {

    private final DataSource dataSource;

    public LocalBoardAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public WorkItem getItem(WorkItemRef ref) {
        try (Connection c = dataSource.getConnection()) {
            return readItem(c, ref.profile(), ref.boardId());
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not read item " + ref, e);
        }
    }

    @Override
    public List<Comment> listComments(WorkItemRef ref) {
        try (Connection c = dataSource.getConnection()) {
            requireItem(c, ref.profile(), ref.boardId());
            return readComments(c, ref.profile(), ref.boardId());
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not list comments for " + ref, e);
        }
    }

    @Override
    public List<WorkItem> search(String profile, String query) {
        String needle = query == null ? "" : query;
        List<WorkItem> results = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT board_id, kind, title, description, canonical_state, parent_id, area_path "
                             + "FROM local_board_items WHERE profile = ? AND title ILIKE ? ORDER BY board_id")) {
            ps.setString(1, profile);
            ps.setString(2, "%" + needle + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new WorkItem(
                            rs.getString("board_id"),
                            rs.getString("kind"),
                            rs.getString("title"),
                            rs.getString("description"),
                            CanonicalState.fromWireValue(rs.getString("canonical_state")),
                            rs.getString("parent_id"),
                            rs.getString("area_path"),
                            List.of()));
                }
            }
            // Fill comments after the item ResultSet is closed, matching getItem.
            for (int i = 0; i < results.size(); i++) {
                WorkItem item = results.get(i);
                results.set(i, new WorkItem(item.id(), item.kind(), item.title(), item.description(),
                        item.state(), item.parentId(), item.areaPath(), readComments(c, profile, item.id())));
            }
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not search board " + profile + " for \"" + query + "\"", e);
        }
        return results;
    }

    // ------------------------------------------------------------------ writes

    /** Creates a new item, or - when the caller passes {@code _idempotencyKey} - returns the item
     * from an earlier call with the same key unchanged (at-least-once Temporal activities rely on
     * this). */
    @Override
    public WorkItem createItem(String profile, String kind, Map<String, Object> fields, String parentBoardId) {
        String title = String.valueOf(fields.getOrDefault("title", ""));
        String description = String.valueOf(fields.getOrDefault("description", ""));
        String areaPath = String.valueOf(fields.getOrDefault("areaPath", ""));
        Object rawKey = fields.get("_idempotencyKey");
        if (rawKey == null) {
            return createPlain(profile, kind, title, description, areaPath, parentBoardId);
        }
        return createIdempotent(profile, kind, title, description, areaPath, parentBoardId, String.valueOf(rawKey));
    }

    private WorkItem createPlain(String profile, String kind, String title, String description,
                                 String areaPath, String parentBoardId) {
        try (Connection c = dataSource.getConnection()) {
            String id = insertItem(c, profile, kind, title, description, areaPath, parentBoardId, null);
            return readItem(c, profile, id);
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not create " + kind + " in " + profile, e);
        }
    }

    private WorkItem createIdempotent(String profile, String kind, String title, String description,
                                      String areaPath, String parentBoardId, String idempotencyKey) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                String existing = selectByKey(c, profile, idempotencyKey);
                if (existing != null) {
                    c.commit();
                    return readItem(c, profile, existing);
                }
                String id = insertItem(c, profile, kind, title, description, areaPath, parentBoardId, idempotencyKey);
                if (id == null) {
                    // A concurrent duplicate won the idempotency-key race (ON CONFLICT blocked until
                    // that transaction resolved); re-read the winning row unchanged.
                    id = selectByKey(c, profile, idempotencyKey);
                    if (id == null) {
                        throw new LocalBoardAdapterException("Idempotent create for key " + idempotencyKey
                                + " in " + profile + " lost the race but no winning row was found");
                    }
                }
                c.commit();
                return readItem(c, profile, id);
            } catch (SQLException e) {
                c.rollback();
                throw new LocalBoardAdapterException("Could not create " + kind + " in " + profile, e);
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not create " + kind + " in " + profile, e);
        }
    }

    /** Inserts one item. Returns the winning board id, or {@code null} when {@code idempotencyKey}
     * is non-null and a concurrent duplicate already holds it (caller re-reads the winner). Retries
     * minted-id collisions with explicit/imported board ids (e.g. a webhook-provided "4412"). */
    private String insertItem(Connection c, String profile, String kind, String title, String description,
                              String areaPath, String parentBoardId, String idempotencyKey) throws SQLException {
        while (true) {
            long minted = nextId(c);
            int inserted;
            if (idempotencyKey == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO local_board_items (profile, board_id, kind, title, description, canonical_state, parent_id, area_path, idempotency_key, rev, comment_seq) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, 0) ON CONFLICT (profile, board_id) DO NOTHING")) {
                    ps.setString(1, profile);
                    ps.setString(2, String.valueOf(minted));
                    ps.setString(3, kind);
                    ps.setString(4, title);
                    ps.setString(5, description);
                    ps.setString(6, CanonicalState.NEW.wireValue());
                    ps.setString(7, parentBoardId);
                    ps.setString(8, areaPath);
                    inserted = ps.executeUpdate();
                }
                if (inserted > 0) {
                    return String.valueOf(minted);
                }
                // minted id collided with an existing board id - retry with a fresh id.
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO local_board_items (profile, board_id, kind, title, description, canonical_state, parent_id, area_path, idempotency_key, rev, comment_seq) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0) ON CONFLICT DO NOTHING")) {
                    ps.setString(1, profile);
                    ps.setString(2, String.valueOf(minted));
                    ps.setString(3, kind);
                    ps.setString(4, title);
                    ps.setString(5, description);
                    ps.setString(6, CanonicalState.NEW.wireValue());
                    ps.setString(7, parentBoardId);
                    ps.setString(8, areaPath);
                    ps.setString(9, idempotencyKey);
                    inserted = ps.executeUpdate();
                }
                if (inserted > 0) {
                    return String.valueOf(minted);
                }
                // Conflict. If the idempotency key now maps to an existing row, this call lost the
                // dedup race (do NOT burn more sequence values). Otherwise the minted board id
                // collided with an existing row - retry.
                if (selectByKey(c, profile, idempotencyKey) != null) {
                    return null;
                }
            }
        }
    }

    @Override
    public void updateFields(WorkItemRef ref, Map<String, Object> fields) {
        try (Connection c = dataSource.getConnection()) {
            updateFieldsOnConnection(c, ref.profile(), ref.boardId(), fields);
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not update fields for " + ref, e);
        }
    }

    @Override
    public void transition(WorkItemRef ref, CanonicalState state) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE local_board_items SET canonical_state = ? WHERE profile = ? AND board_id = ?")) {
            ps.setString(1, state.wireValue());
            ps.setString(2, ref.profile());
            ps.setString(3, ref.boardId());
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("No such work item: " + ref);
            }
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not transition " + ref + " to " + state, e);
        }
    }

    @Override
    public CommentRef addComment(WorkItemRef ref, String body, String author) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                CommentRef result = addCommentOnConnection(c, ref.profile(), ref.boardId(), body, author);
                c.commit();
                return result;
            } catch (SQLException e) {
                c.rollback();
                throw new LocalBoardAdapterException("Could not add comment to " + ref, e);
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not add comment to " + ref, e);
        }
    }

    @Override
    public void link(WorkItemRef ref, String otherBoardId, String relation) {
        // Recorded implicitly via parentId on createItem for the pilot's one relation (parent-child).
    }

    @Override
    public AttachmentRef attach(WorkItemRef ref, String fileName, byte[] content) {
        try (Connection c = dataSource.getConnection()) {
            requireItem(c, ref.profile(), ref.boardId()); // validates existence
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not attach to " + ref, e);
        }
        return new AttachmentRef(ref.boardId() + "/" + fileName,
                "memory://" + ref.profile() + "/" + ref.boardId() + "/" + fileName);
    }

    // ------------------------------------------------------------------ webhook

    @SuppressWarnings("unchecked")
    @Override
    public CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent) {
        String kindWire = String.valueOf(rawEvent.get("kind"));
        String boardId = String.valueOf(rawEvent.get("boardId"));
        CanonicalEvent.Kind kind = switch (kindWire) {
            case "item.created" -> CanonicalEvent.Kind.ITEM_CREATED;
            case "item.updated" -> CanonicalEvent.Kind.ITEM_UPDATED;
            case "comment.added" -> CanonicalEvent.Kind.COMMENT_ADDED;
            default -> throw new IllegalArgumentException("Unknown webhook kind: " + kindWire);
        };
        long rev = rawEvent.get("rev") == null ? -1 : ((Number) rawEvent.get("rev")).longValue();

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (rev >= 0) {
                    String existingKind = webhookKind(c, profile, boardId, rev);
                    if (existingKind != null) {
                        if (existingKind.equals(kind.wireValue())) {
                            // Replay: return the same event, without re-applying the mutation.
                            c.commit();
                            return new CanonicalEvent(new WorkItemRef(profile, boardId), kind, rev);
                        }
                        throw new LocalBoardAdapterException("Webhook revision " + rev + " for " + profile + "/"
                                + boardId + " is already recorded as " + existingKind
                                + " (conflicting kind " + kind.wireValue() + ")");
                    }
                } else {
                    rev = allocateRev(c, profile, boardId);
                }
                applyMutation(c, profile, boardId, kind, rawEvent);
                insertWebhook(c, profile, boardId, kind.wireValue(), rev);
                c.commit();
                return new CanonicalEvent(new WorkItemRef(profile, boardId), kind, rev);
            } catch (SQLException e) {
                c.rollback();
                throw new LocalBoardAdapterException(
                        "Could not apply webhook " + kindWire + " for " + profile + "/" + boardId, e);
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new LocalBoardAdapterException("Could not open transaction for webhook " + kindWire, e);
        }
    }

    /** Allocates the next revision for an auto-numbered (no explicit {@code rev}) webhook. Must be
     * monotonic and never decrease, so it serializes on the item row when one exists; a brand-new
     * {@code item.created} has no row to lock yet, so it falls back to a transaction-scoped
     * advisory lock keyed on {@code profile:boardId}. */
    private long allocateRev(Connection c, String profile, String boardId) throws SQLException {
        boolean locked = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM local_board_items WHERE profile = ? AND board_id = ? FOR UPDATE")) {
            ps.setString(1, profile);
            ps.setString(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                locked = rs.next();
            }
        }
        if (!locked) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
                ps.setString(1, profile + ":" + boardId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(rev), 0) + 1 FROM local_board_webhooks WHERE profile = ? AND board_id = ?")) {
            ps.setString(1, profile);
            ps.setString(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void applyMutation(Connection c, String profile, String boardId, CanonicalEvent.Kind kind,
                               Map<String, Object> rawEvent) throws SQLException {
        switch (kind) {
            case ITEM_CREATED -> {
                String itemKind = String.valueOf(rawEvent.getOrDefault("itemKind", "feature"));
                String title = String.valueOf(rawEvent.getOrDefault("title", ""));
                String description = String.valueOf(rawEvent.getOrDefault("description", ""));
                String areaPath = String.valueOf(rawEvent.getOrDefault("areaPath", ""));
                // Idempotent: a duplicate/replayed item.created must never reset an item that has
                // already progressed (comments, state).
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO local_board_items (profile, board_id, kind, title, description, canonical_state, parent_id, area_path, idempotency_key, rev, comment_seq) "
                                + "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, 0, 0) ON CONFLICT (profile, board_id) DO NOTHING")) {
                    ps.setString(1, profile);
                    ps.setString(2, boardId);
                    ps.setString(3, itemKind);
                    ps.setString(4, title);
                    ps.setString(5, description);
                    ps.setString(6, CanonicalState.NEW.wireValue());
                    ps.setString(7, areaPath);
                    ps.executeUpdate();
                }
            }
            case ITEM_UPDATED -> updateFieldsOnConnection(c, profile, boardId,
                    (Map<String, Object>) rawEvent.getOrDefault("fields", Map.of()));
            case COMMENT_ADDED -> addCommentOnConnection(c, profile, boardId,
                    String.valueOf(rawEvent.get("text")), String.valueOf(rawEvent.get("author")));
        }
    }

    private void updateFieldsOnConnection(Connection c, String profile, String boardId,
                                          Map<String, Object> fields) throws SQLException {
        requireItem(c, profile, boardId);
        if (fields.containsKey("title")) {
            updateField(c, profile, boardId, "title", String.valueOf(fields.get("title")));
        }
        if (fields.containsKey("description")) {
            updateField(c, profile, boardId, "description", String.valueOf(fields.get("description")));
        }
        if (fields.containsKey("areaPath")) {
            updateField(c, profile, boardId, "area_path", String.valueOf(fields.get("areaPath")));
        }
    }

    private void updateField(Connection c, String profile, String boardId, String column, String value)
            throws SQLException {
        // column is one of three hardcoded literals above, never caller input.
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE local_board_items SET " + column + " = ? WHERE profile = ? AND board_id = ?")) {
            ps.setString(1, value);
            ps.setString(2, profile);
            ps.setString(3, boardId);
            ps.executeUpdate();
        }
    }

    private CommentRef addCommentOnConnection(Connection c, String profile, String boardId,
                                              String body, String author) throws SQLException {
        long ordinal;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE local_board_items SET comment_seq = comment_seq + 1 WHERE profile = ? AND board_id = ? RETURNING comment_seq")) {
            ps.setString(1, profile);
            ps.setString(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("No such work item: " + new WorkItemRef(profile, boardId));
                }
                ordinal = rs.getLong(1);
            }
        }
        String commentId = boardId + "-c" + ordinal;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO local_board_comments (profile, board_id, id, ordinal, author, role, stage, target, text, intent, blocking, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, profile);
            ps.setString(2, boardId);
            ps.setString(3, commentId);
            ps.setLong(4, ordinal);
            ps.setString(5, author);
            ps.setString(6, "");
            ps.setString(7, "grill");
            ps.setString(8, "note");
            ps.setString(9, body);
            ps.setString(10, Comment.Intent.NOTE.wireValue());
            ps.setBoolean(11, false);
            ps.setInt(12, 0);
            ps.executeUpdate();
        }
        return new CommentRef(commentId);
    }

    // ------------------------------------------------------------------ SQL helpers

    private void requireItem(Connection c, String profile, String boardId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM local_board_items WHERE profile = ? AND board_id = ?")) {
            ps.setString(1, profile);
            ps.setString(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("No such work item: " + new WorkItemRef(profile, boardId));
                }
            }
        }
    }

    private WorkItem readItem(Connection c, String profile, String boardId) throws SQLException {
        String id, kind, title, description, parentId, areaPath;
        CanonicalState state;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT board_id, kind, title, description, canonical_state, parent_id, area_path "
                        + "FROM local_board_items WHERE profile = ? AND board_id = ?")) {
            ps.setString(1, profile);
            ps.setString(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("No such work item: " + new WorkItemRef(profile, boardId));
                }
                id = rs.getString("board_id");
                kind = rs.getString("kind");
                title = rs.getString("title");
                description = rs.getString("description");
                state = CanonicalState.fromWireValue(rs.getString("canonical_state"));
                parentId = rs.getString("parent_id");
                areaPath = rs.getString("area_path");
            }
        }
        return new WorkItem(id, kind, title, description, state, parentId, areaPath, readComments(c, profile, boardId));
    }

    private List<Comment> readComments(Connection c, String profile, String boardId) throws SQLException {
        List<Comment> comments = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, author, role, stage, target, text, intent, blocking, version "
                        + "FROM local_board_comments WHERE profile = ? AND board_id = ? ORDER BY ordinal")) {
            ps.setString(1, profile);
            ps.setString(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    comments.add(new Comment(
                            rs.getString("id"),
                            rs.getString("author"),
                            rs.getString("role"),
                            rs.getString("stage"),
                            rs.getString("target"),
                            rs.getString("text"),
                            Comment.Intent.fromWire(rs.getString("intent")),
                            rs.getBoolean("blocking"),
                            rs.getInt("version")));
                }
            }
        }
        return comments;
    }

    private String selectByKey(Connection c, String profile, String idempotencyKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT board_id FROM local_board_items WHERE profile = ? AND idempotency_key = ?")) {
            ps.setString(1, profile);
            ps.setString(2, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("board_id") : null;
            }
        }
    }

    private String webhookKind(Connection c, String profile, String boardId, long rev) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT kind FROM local_board_webhooks WHERE profile = ? AND board_id = ? AND rev = ?")) {
            ps.setString(1, profile);
            ps.setString(2, boardId);
            ps.setLong(3, rev);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("kind") : null;
            }
        }
    }

    private void insertWebhook(Connection c, String profile, String boardId, String kind, long rev)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO local_board_webhooks (profile, board_id, rev, kind) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, profile);
            ps.setString(2, boardId);
            ps.setLong(3, rev);
            ps.setString(4, kind);
            ps.executeUpdate();
        }
    }

    private long nextId(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT nextval('local_board_id_seq')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
