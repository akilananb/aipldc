package ai.pdlc.adapters.inmemory;

import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CommitRef;
import ai.pdlc.core.domain.Diff;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.PRStatus;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.RepoPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Git-less in-memory {@link RepoPort}: keeps files per branch in a map. {@link #writeFiles} returns
 * a fake {@link CommitRef} of sha256(content). Backs the {@code local} profile and every automated
 * test.
 */
public final class InMemoryRepoAdapter implements RepoPort {

    private final Map<String, Map<String, String>> branches = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> commitSnapshots = new ConcurrentHashMap<>();
    private final Map<String, PRRecord> prs = new ConcurrentHashMap<>();
    private final AtomicLong prSeq = new AtomicLong(0);

    private record PRRecord(String branch, String target, String title, String body, String diff) {
    }

    private Map<String, String> branch(String name) {
        return branches.computeIfAbsent(name, b -> new ConcurrentHashMap<>());
    }

    @Override
    public String readFile(String ref, String path) {
        // Immutable commit sha (from a prior writeFiles) takes precedence, matching real git's
        // "any ref, including a sha, resolves to that exact snapshot" - lets historical artifact
        // versions stay readable by their stored git_ref even after the branch head has moved on.
        Map<String, String> snapshot = commitSnapshots.get(ref);
        Map<String, String> source = snapshot != null ? snapshot : branch(ref);
        String content = source.get(path);
        if (content == null) {
            throw new IllegalArgumentException("No such file " + path + " on ref " + ref);
        }
        return content;
    }

    @Override
    public CommitRef writeFiles(String branchName, Map<String, String> files, String message, String authorName) {
        Map<String, String> target = branch(branchName);
        target.putAll(files);
        String sha = sha256(canonicalize(target));
        commitSnapshots.put(sha, Map.copyOf(target));
        return new CommitRef(sha);
    }

    @Override
    public void createBranch(String from, String name) {
        branches.put(name, new ConcurrentHashMap<>(branch(from)));
    }

    @Override
    public PRRef openPR(String branchName, String target, String title, String body) {
        String id = String.valueOf(prSeq.incrementAndGet());
        prs.put(id, new PRRecord(branchName, target, title, body, ""));
        return new PRRef(id, "memory://pr/" + id);
    }

    @Override
    public void commentOnPR(String prId, String body, Integer line) {
        // unused in the pilot (phase 3+); no-op store would need a comments list if exercised later.
    }

    @Override
    public Diff getDiff(String prId) {
        PRRecord pr = prs.get(prId);
        return new Diff(pr == null ? "" : pr.diff());
    }

    @Override
    public PRStatus getPRStatus(String prId) {
        return prs.containsKey(prId) ? new PRStatus("open", true) : new PRStatus("not-found", false);
    }

    @Override
    public CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent) {
        String kindWire = String.valueOf(rawEvent.get("kind"));
        String boardId = String.valueOf(rawEvent.getOrDefault("boardId", ""));
        long rev = rawEvent.get("rev") == null ? 1 : ((Number) rawEvent.get("rev")).longValue();
        CanonicalEvent.Kind kind = switch (kindWire) {
            case "item.updated" -> CanonicalEvent.Kind.ITEM_UPDATED;
            case "comment.added" -> CanonicalEvent.Kind.COMMENT_ADDED;
            default -> CanonicalEvent.Kind.ITEM_UPDATED;
        };
        return new CanonicalEvent(new WorkItemRef(profile, boardId), kind, rev);
    }

    private static String canonicalize(Map<String, String> files) {
        // Deterministic content hash regardless of map iteration order.
        Map<String, String> sorted = new TreeMap<>(files);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('\u0000').append(e.getValue()).append('\u0000');
        }
        return sb.toString();
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
