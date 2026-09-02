package ai.pdlc.adapters.inmemory;

import ai.pdlc.core.domain.AttachmentRef;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.CommentRef;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Git-less, board-less in-memory {@link BoardPort}: keeps items in a map. Backs the {@code local}
 * profile and every automated test. {@link #onWebhook} both normalizes the raw event AND (since
 * there is no real board to be the source of truth) applies the event's own content to the store -
 * the local webhook payload IS the board write for this adapter.
 */
public final class InMemoryBoardAdapter implements BoardPort {

    /** A single in-memory board item, mutable under its own lock. */
    private static final class Item {
        String id;
        String kind;
        String title;
        String description;
        CanonicalState state;
        String parentId;
        String areaPath;
        final List<Comment> comments = new ArrayList<>();
        final AtomicLong rev = new AtomicLong(0);
        final AtomicLong commentSeq = new AtomicLong(0);

        synchronized WorkItem snapshot() {
            return new WorkItem(id, kind, title, description, state, parentId, areaPath, List.copyOf(comments));
        }
    }

    private final Map<String, Map<String, Item>> byProfile = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> idSeqByProfile = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> idempotencyByProfile = new ConcurrentHashMap<>();
    private final long startingSequence;

    public InMemoryBoardAdapter() {
        this(4412);
    }

    /** {@code startingSequence} - the first auto-minted board id this instance hands out. This
     * process's in-memory sequence has no memory of a prior process's ids; a caller backed by
     * durable storage (control-plane's Postgres work_items table) MUST seed this above whatever
     * numeric board id is already on record there, or a restart will reissue an old id and {@code
     * ensureWorkItem}'s (profile, board_id) lookup will silently rebind a brand-new work item to
     * an unrelated pre-existing row. */
    public InMemoryBoardAdapter(long startingSequence) {
        this.startingSequence = startingSequence;
    }

    private Map<String, Item> itemsFor(String profile) {
        return byProfile.computeIfAbsent(profile, p -> new ConcurrentHashMap<>());
    }

    private Item requireItem(WorkItemRef ref) {
        Item item = itemsFor(ref.profile()).get(ref.boardId());
        if (item == null) {
            throw new IllegalArgumentException("No such work item: " + ref);
        }
        return item;
    }

    @Override
    public WorkItem getItem(WorkItemRef ref) {
        return requireItem(ref).snapshot();
    }

    /** Creates a new item, or - when the caller passes {@code _idempotencyKey} - returns the item
     * from an earlier call with the same key unchanged. Callers that create board cards from a
     * Temporal activity (publishTasks/publishStory/publishReleasePack/fileMonitorCards) rely on this:
     * activities are at-least-once, and without dedup a retry silently doubles every card. */
    @Override
    public WorkItem createItem(String profile, String kind, Map<String, Object> fields, String parentBoardId) {
        Map<String, Item> items = itemsFor(profile);
        Object rawKey = fields.get("_idempotencyKey");
        if (rawKey == null) {
            return createNewItem(profile, items, kind, fields, parentBoardId).snapshot();
        }
        String idempotencyKey = String.valueOf(rawKey);
        Map<String, String> index = idempotencyByProfile.computeIfAbsent(profile, p -> new ConcurrentHashMap<>());
        String id = index.computeIfAbsent(idempotencyKey,
                k -> createNewItem(profile, items, kind, fields, parentBoardId).id);
        return items.get(id).snapshot();
    }

    private Item createNewItem(String profile, Map<String, Item> items, String kind, Map<String, Object> fields, String parentBoardId) {
        AtomicLong seq = idSeqByProfile.computeIfAbsent(profile, p -> new AtomicLong(startingSequence));
        String id;
        do {
            id = String.valueOf(seq.getAndIncrement());
        } while (items.containsKey(id)); // avoid colliding with a webhook-provided fixture id (e.g. 4412)
        Item item = new Item();
        item.id = id;
        item.kind = kind;
        item.title = String.valueOf(fields.getOrDefault("title", ""));
        item.description = String.valueOf(fields.getOrDefault("description", ""));
        item.state = CanonicalState.NEW;
        item.parentId = parentBoardId;
        item.areaPath = String.valueOf(fields.getOrDefault("areaPath", ""));
        items.put(id, item);
        return item;
    }

    @Override
    public void updateFields(WorkItemRef ref, Map<String, Object> fields) {
        Item item = requireItem(ref);
        synchronized (item) {
            if (fields.containsKey("title")) {
                item.title = String.valueOf(fields.get("title"));
            }
            if (fields.containsKey("description")) {
                item.description = String.valueOf(fields.get("description"));
            }
            if (fields.containsKey("areaPath")) {
                item.areaPath = String.valueOf(fields.get("areaPath"));
            }
        }
    }

    @Override
    public void transition(WorkItemRef ref, CanonicalState state) {
        Item item = requireItem(ref);
        synchronized (item) {
            item.state = state;
        }
    }

    @Override
    public List<Comment> listComments(WorkItemRef ref) {
        Item item = requireItem(ref);
        synchronized (item) {
            return List.copyOf(item.comments);
        }
    }

    @Override
    public CommentRef addComment(WorkItemRef ref, String body, String author) {
        Item item = requireItem(ref);
        synchronized (item) {
            String commentId = ref.boardId() + "-c" + item.commentSeq.incrementAndGet();
            item.comments.add(new Comment(commentId, author, "", "grill", "note", body, Comment.Intent.NOTE, false, 0));
            return new CommentRef(commentId);
        }
    }

    @Override
    public void link(WorkItemRef ref, String otherBoardId, String relation) {
        // Recorded implicitly via parentId on createItem for the pilot's one relation (parent-child).
    }

    @Override
    public AttachmentRef attach(WorkItemRef ref, String fileName, byte[] content) {
        requireItem(ref); // validates existence
        return new AttachmentRef(ref.boardId() + "/" + fileName, "memory://" + ref.profile() + "/" + ref.boardId() + "/" + fileName);
    }

    @SuppressWarnings("unchecked")
    @Override
    public CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent) {
        String kindWire = String.valueOf(rawEvent.get("kind"));
        String boardId = String.valueOf(rawEvent.get("boardId"));
        long rev = rawEvent.get("rev") == null ? nextRev(profile, boardId) : ((Number) rawEvent.get("rev")).longValue();

        CanonicalEvent.Kind kind = switch (kindWire) {
            case "item.created" -> CanonicalEvent.Kind.ITEM_CREATED;
            case "item.updated" -> CanonicalEvent.Kind.ITEM_UPDATED;
            case "comment.added" -> CanonicalEvent.Kind.COMMENT_ADDED;
            default -> throw new IllegalArgumentException("Unknown webhook kind: " + kindWire);
        };

        switch (kind) {
            case ITEM_CREATED -> {
                // Idempotent: a replayed/duplicate item.created webhook (e.g. dedupe happens after
                // this normalization step, upstream) must not reset an item that already progressed
                // (comments, state) - only create it the first time.
                itemsFor(profile).computeIfAbsent(boardId, id -> {
                    Item item = new Item();
                    item.id = id;
                    item.kind = String.valueOf(rawEvent.getOrDefault("itemKind", "feature"));
                    item.title = String.valueOf(rawEvent.getOrDefault("title", ""));
                    item.description = String.valueOf(rawEvent.getOrDefault("description", ""));
                    item.state = CanonicalState.NEW;
                    item.areaPath = String.valueOf(rawEvent.getOrDefault("areaPath", ""));
                    return item;
                });
            }
            case ITEM_UPDATED -> updateFields(new WorkItemRef(profile, boardId),
                    (Map<String, Object>) rawEvent.getOrDefault("fields", Map.of()));
            case COMMENT_ADDED -> addComment(new WorkItemRef(profile, boardId),
                    String.valueOf(rawEvent.get("text")), String.valueOf(rawEvent.get("author")));
        }

        return new CanonicalEvent(new WorkItemRef(profile, boardId), kind, rev);
    }

    private long nextRev(String profile, String boardId) {
        Item existing = itemsFor(profile).get(boardId);
        return existing == null ? 1 : existing.rev.incrementAndGet();
    }

    @Override
    public List<WorkItem> search(String profile, String query) {
        String needle = query == null ? "" : query.toLowerCase();
        List<WorkItem> results = new ArrayList<>();
        for (Item item : itemsFor(profile).values()) {
            WorkItem snapshot = item.snapshot();
            if (snapshot.title().toLowerCase().contains(needle)) {
                results.add(snapshot);
            }
        }
        return results;
    }
}
