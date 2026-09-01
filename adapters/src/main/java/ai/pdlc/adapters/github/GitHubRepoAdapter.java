package ai.pdlc.adapters.github;

import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CommitRef;
import ai.pdlc.core.domain.Diff;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.PRStatus;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.RepoPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * GitHub {@link RepoPort} — tech-stack §4 adapter notes: contents API for {@link #readFile}, git
 * data API (blobs/trees/commits/refs) for {@link #writeFiles} (single commit, author = bot
 * identity, human name folded into the commit message), {@link #createBranch}.
 * {@code openPR/commentOnPR/getDiff/getPRStatus} are implemented thin — unused until build-order
 * phase 3.
 */
public final class GitHubRepoAdapter implements RepoPort {

    private static final String BOT_AUTHOR_NAME = "pdlc-bot";
    private static final String BOT_AUTHOR_EMAIL = "pdlc-bot@users.noreply.github.com";

    private final String apiBaseUrl;
    private final String owner;
    private final String repo;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubRepoAdapter(String repoUrl, String token) {
        this(repoUrl, token, HttpClient.newHttpClient());
    }

    public GitHubRepoAdapter(String repoUrl, String token, HttpClient http) {
        this("https://api.github.com", repoUrl, token, http);
    }

    /** Testing seam: point at a mock server instead of {@code https://api.github.com}. */
    public GitHubRepoAdapter(String apiBaseUrl, String repoUrl, String token, HttpClient http) {
        String[] ownerRepo = parseOwnerRepo(repoUrl);
        this.apiBaseUrl = apiBaseUrl;
        this.owner = ownerRepo[0];
        this.repo = ownerRepo[1];
        this.token = token;
        this.http = http;
    }

    private static String[] parseOwnerRepo(String repoUrl) {
        String path = URI.create(repoUrl).getPath(); // /acme/orders-service
        String[] parts = path.replaceAll("^/", "").replaceAll("\\.git$", "").split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Cannot parse owner/repo from " + repoUrl);
        }
        return new String[]{parts[0], parts[1]};
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(apiBaseUrl + "/repos/" + owner + "/" + repo + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
    }


    private JsonNode send(HttpRequest request) {
        return sendRaw(request, "application/json");
    }

    private JsonNode sendRaw(HttpRequest request, String expectedAccept) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new GitHubAdapterException("GitHub request failed: " + request.method() + " " + request.uri()
                        + " -> " + response.statusCode() + ": " + response.body());
            }
            if (!"application/json".equals(expectedAccept)) {
                ObjectNode wrapper = mapper.createObjectNode();
                wrapper.put("raw", response.body());
                return wrapper;
            }
            return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GitHubAdapterException("GitHub request failed: " + request.method() + " " + request.uri(), e);
        }
    }

    @Override
    public String readFile(String ref, String path) {
        JsonNode body = send(request("/contents/" + path + "?ref=" + ref).GET().build());
        String base64 = body.path("content").asText().replace("\n", "");
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    @Override
    public CommitRef writeFiles(String branch, Map<String, String> files, String message, String authorName) {
        String parentSha = refSha(branch);
        String baseTreeSha = send(request("/git/commits/" + parentSha).GET().build()).path("tree").path("sha").asText();

        ArrayNode treeEntries = mapper.createArrayNode();
        for (Map.Entry<String, String> file : files.entrySet()) {
            ObjectNode blobPayload = mapper.createObjectNode();
            blobPayload.put("content", file.getValue());
            blobPayload.put("encoding", "utf-8");
            String blobSha = send(request("/git/blobs")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(blobPayload.toString()))
                    .build()).path("sha").asText();

            ObjectNode entry = mapper.createObjectNode();
            entry.put("path", file.getKey());
            entry.put("mode", "100644");
            entry.put("type", "blob");
            entry.put("sha", blobSha);
            treeEntries.add(entry);
        }

        ObjectNode treePayload = mapper.createObjectNode();
        treePayload.put("base_tree", baseTreeSha);
        treePayload.set("tree", treeEntries);
        String newTreeSha = send(request("/git/trees")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(treePayload.toString()))
                .build()).path("sha").asText();

        ObjectNode author = mapper.createObjectNode();
        author.put("name", BOT_AUTHOR_NAME);
        author.put("email", BOT_AUTHOR_EMAIL);
        ObjectNode commitPayload = mapper.createObjectNode();
        commitPayload.put("message", message + "\n\nOn behalf of: " + authorName);
        commitPayload.put("tree", newTreeSha);
        ArrayNode parents = mapper.createArrayNode();
        parents.add(parentSha);
        commitPayload.set("parents", parents);
        commitPayload.set("author", author);
        String newCommitSha = send(request("/git/commits")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(commitPayload.toString()))
                .build()).path("sha").asText();

        ObjectNode refPayload = mapper.createObjectNode();
        refPayload.put("sha", newCommitSha);
        send(request("/git/refs/heads/" + branch)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(refPayload.toString()))
                .build());

        return new CommitRef(newCommitSha);
    }

    private String refSha(String branch) {
        return send(request("/git/ref/heads/" + branch).GET().build()).path("object").path("sha").asText();
    }

    @Override
    public void createBranch(String from, String name) {
        String fromSha = refSha(from);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("ref", "refs/heads/" + name);
        payload.put("sha", fromSha);
        send(request("/git/refs")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build());
    }

    @Override
    public PRRef openPR(String branch, String target, String title, String body) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("title", title);
        payload.put("head", branch);
        payload.put("base", target);
        payload.put("body", body);
        JsonNode response = send(request("/pulls")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build());
        return new PRRef(response.path("number").asText(), response.path("html_url").asText());
    }

    @Override
    public void commentOnPR(String prId, String body, Integer line) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("body", body);
        send(request("/issues/" + prId + "/comments")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build());
    }

    @Override
    public Diff getDiff(String prId) {
        JsonNode response = sendRaw(request("/pulls/" + prId)
                        .header("Accept", "application/vnd.github.diff")
                        .GET().build(),
                "application/vnd.github.diff");
        return new Diff(response.path("raw").asText());
    }

    @Override
    public PRStatus getPRStatus(String prId) {
        JsonNode response = send(request("/pulls/" + prId).GET().build());
        return new PRStatus(response.path("state").asText(), response.path("mergeable").asBoolean(false));
    }

    @Override
    public CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent) {
        // GitHub webhook payload shape: { action, pull_request: {...} } or { ref, commits: [...] }
        String action = String.valueOf(rawEvent.getOrDefault("action", ""));
        CanonicalEvent.Kind kind = switch (action) {
            case "opened" -> CanonicalEvent.Kind.ITEM_CREATED;
            case "created" -> CanonicalEvent.Kind.COMMENT_ADDED;
            default -> CanonicalEvent.Kind.ITEM_UPDATED;
        };
        String boardId = String.valueOf(rawEvent.getOrDefault("number", ""));
        return new CanonicalEvent(new WorkItemRef(profile, boardId), kind, 0L);
    }
}
