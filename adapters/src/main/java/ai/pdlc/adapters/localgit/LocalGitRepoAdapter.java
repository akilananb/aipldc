package ai.pdlc.adapters.localgit;

import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CommitRef;
import ai.pdlc.core.domain.Diff;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.PRStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real-git {@link ai.pdlc.core.port.RepoPort} — build-order phase 3's {@code local-git} provider.
 * Uses plain {@code git} plumbing (hash-object/read-tree/write-tree/commit-tree/update-ref)
 * against a working repo on the local filesystem, the same blob-tree-commit-ref shape as
 * {@link ai.pdlc.adapters.github.GitHubRepoAdapter}'s git-data-API calls, so both adapters keep
 * the exact contract in {@link ai.pdlc.core.port.RepoPort}. Never touches the repo's working tree
 * or index (all plumbing runs against a private temp index file) so it is safe to call
 * concurrently with a build worker that has its own {@code git worktree} checkouts open.
 *
 * <p>{@code openPR}/{@code commentOnPR}/{@code getDiff}/{@code getPRStatus} have no real remote to
 * delegate to, so PR records are owned entirely by this adapter: persisted as JSON files under
 * {@code <repo>/.git/pdlc-prs/}, a directory git itself never tracks or touches.
 */
public final class LocalGitRepoAdapter implements ai.pdlc.core.port.RepoPort {

    private static final String BOT_AUTHOR_NAME = "pdlc-bot";
    private static final String BOT_AUTHOR_EMAIL = "pdlc-bot@local";

    private final Path repoPath;
    private final Path prDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong prSeq;

    public LocalGitRepoAdapter(String repoPath) {
        this.repoPath = Path.of(repoPath);
        if (!Files.isDirectory(this.repoPath.resolve(".git"))) {
            throw new LocalGitAdapterException("Not a git repository: " + repoPath);
        }
        this.prDir = this.repoPath.resolve(".git").resolve("pdlc-prs");
        try {
            Files.createDirectories(prDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.prSeq = new AtomicLong(highestExistingPrId());
    }

    private long highestExistingPrId() {
        try (var stream = Files.list(prDir)) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .mapToLong(name -> Long.parseLong(name.substring(0, name.length() - ".json".length())))
                    .max().orElse(0L);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String readFile(String ref, String path) {
        return git(List.of("show", ref + ":" + path), null);
    }

    @Override
    public CommitRef writeFiles(String branch, Map<String, String> files, String message, String authorName) {
        String parentSha = git(List.of("rev-parse", "--verify", branch), null).strip();
        Path index = tempIndex();
        try {
            git(List.of("read-tree", parentSha), index);
            for (Map.Entry<String, String> file : files.entrySet()) {
                String blobSha = git(List.of("hash-object", "-w", "--stdin"), null, file.getValue()).strip();
                git(List.of("update-index", "--add", "--cacheinfo", "100644," + blobSha + "," + file.getKey()), index);
            }
            String newTreeSha = git(List.of("write-tree"), index).strip();
            String fullMessage = message + "\n\nOn behalf of: " + authorName;
            String newCommitSha = gitWithAuthor(
                    List.of("commit-tree", newTreeSha, "-p", parentSha, "-m", fullMessage)).strip();
            git(List.of("update-ref", "refs/heads/" + branch, newCommitSha, parentSha), null);
            return new CommitRef(newCommitSha);
        } finally {
            deleteQuietly(index);
        }
    }

    @Override
    public void createBranch(String from, String name) {
        String fromSha = git(List.of("rev-parse", "--verify", from), null).strip();
        git(List.of("update-ref", "refs/heads/" + name, fromSha), null);
    }

    @Override
    public PRRef openPR(String branch, String target, String title, String body) {
        long id = prSeq.incrementAndGet();
        ObjectNode record = mapper.createObjectNode();
        record.put("id", String.valueOf(id));
        record.put("branch", branch);
        record.put("target", target);
        record.put("title", title);
        record.put("body", body);
        record.put("state", "open");
        record.set("comments", mapper.createArrayNode());
        writePrRecord(id, record);
        return new PRRef(String.valueOf(id), "local://prs/" + id);
    }

    @Override
    public void commentOnPR(String prId, String body, Integer line) {
        ObjectNode record = readPrRecord(prId);
        ObjectNode comment = mapper.createObjectNode();
        comment.put("body", body);
        if (line != null) {
            comment.put("line", line);
        }
        ((ArrayNode) record.get("comments")).add(comment);
        writePrRecord(Long.parseLong(prId), record);
    }

    @Override
    public Diff getDiff(String prId) {
        ObjectNode record = readPrRecord(prId);
        String target = record.get("target").asText();
        String branch = record.get("branch").asText();
        return new Diff(git(List.of("diff", target + "..." + branch), null));
    }

    @Override
    public PRStatus getPRStatus(String prId) {
        ObjectNode record = readPrRecord(prId);
        return new PRStatus(record.get("state").asText(), true);
    }

    @Override
    public CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent) {
        throw new UnsupportedOperationException(
                "local-git has no webhook source; the pilot's local profile only drives repo changes"
                        + " through the workflow's own RepoPort calls, never inbound webhooks");
    }

    private ObjectNode readPrRecord(String prId) {
        Path file = prDir.resolve(prId + ".json");
        if (!Files.isRegularFile(file)) {
            throw new LocalGitAdapterException("No such PR: " + prId);
        }
        try {
            return (ObjectNode) mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writePrRecord(long id, ObjectNode record) {
        try {
            Files.writeString(prDir.resolve(id + ".json"), record.toPrettyString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path tempIndex() {
        try {
            Path index = Files.createTempFile("pdlc-local-git-index-", ".idx");
            Files.delete(index); // git read-tree creates it fresh; must not pre-exist as a non-git-index file
            return index;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    private String git(List<String> args, Path indexFile) {
        return git(args, indexFile, null);
    }

    private String gitWithAuthor(List<String> args) {
        return run(args, null, null, Map.of(
                "GIT_AUTHOR_NAME", BOT_AUTHOR_NAME, "GIT_AUTHOR_EMAIL", BOT_AUTHOR_EMAIL,
                "GIT_COMMITTER_NAME", BOT_AUTHOR_NAME, "GIT_COMMITTER_EMAIL", BOT_AUTHOR_EMAIL));
    }

    private String git(List<String> args, Path indexFile, String stdin) {
        return run(args, indexFile, stdin, Map.of());
    }

    private String run(List<String> args, Path indexFile, String stdin, Map<String, String> extraEnv) {
        try {
            List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repoPath.toString()));
            command.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().putAll(extraEnv);
            if (indexFile != null) {
                pb.environment().put("GIT_INDEX_FILE", indexFile.toString());
            }
            Process process = pb.start();
            if (stdin != null) {
                try (var out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new LocalGitAdapterException("git " + String.join(" ", args) + " failed (" + exit + "): " + stderr);
            }
            return stdout;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalGitAdapterException("git " + String.join(" ", args) + " interrupted", e);
        }
    }
}
