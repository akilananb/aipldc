package ai.pdlc.adapters.remoteboard;

import ai.pdlc.core.domain.AttachmentRef;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.CommentRef;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only {@link BoardPort} that fetches real board content from control-plane's own
 * {@code BoardProxyController} ({@code GET /api/board/{profile}/{boardId}[/comments]}) -
 * build-order phase 4 fix. The {@code local} in-memory provider previously gave every process its
 * own empty {@code InMemoryBoardAdapter}; since control-plane and agents are separate JVMs
 * (orchestration-decision §6), agents' context-gathering reads ({@code AgentContext}) always saw
 * an empty board. Harmless while grill/PO ran against the stub-llm gateway (canned responses
 * regardless of context), but a real gap once a real model is wired in - it can only see the
 * item's board id, never its title/description.
 *
 * <p>Reads go through the board proxy, not {@code GET /api/items/{id}} (which is backed by the
 * {@code work_items} Postgres row - not yet created when {@code grillEvaluate}, the very first
 * read, runs; control-plane's own {@code BoardSideEffectsImpl.postGrillQuestions} is what calls
 * {@code ensureWorkItem} to create that row, and it runs one activity after {@code grillEvaluate}).
 *
 * <p>Writes are never issued from the agents process (only {@code BoardSideEffects}, hosted by
 * control-plane, writes - orchestration-decision §6's determinism rule); every write method here
 * throws to make an accidental call fail loudly instead of silently no-op-ing.
 */
public final class RemoteBoardPort implements BoardPort {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public RemoteBoardPort(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public WorkItem getItem(WorkItemRef ref) {
        JsonNode node = getJson("/api/board/" + encode(ref.profile()) + "/" + encode(ref.boardId()));
        String stateWire = textOrNull(node, "state");
        CanonicalState state = stateWire == null ? CanonicalState.NEW : CanonicalState.fromWireValue(stateWire);
        return new WorkItem(ref.boardId(), textOrNull(node, "kind"), textOrNull(node, "title"),
                textOrNull(node, "description"), state, textOrNull(node, "parentId"),
                textOrNull(node, "areaPath"), List.of());
    }

    @Override
    public List<Comment> listComments(WorkItemRef ref) {
        try {
            JsonNode array = getJson("/api/board/" + encode(ref.profile()) + "/" + encode(ref.boardId()) + "/comments");
            List<Comment> comments = new ArrayList<>();
            for (JsonNode c : array) {
                comments.add(mapper.treeToValue(c, Comment.class));
            }
            return comments;
        } catch (IOException e) {
            throw new RemoteBoardPortException("Could not parse board comments for " + ref, e);
        }
    }

    private JsonNode getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new RemoteBoardPortException("GET " + path + " returned " + response.statusCode() + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RemoteBoardPortException("GET " + path + " failed against " + baseUrl, e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // -- unsupported: agents never issues these (BoardSideEffects, on control-plane, owns every write,
    // and search isn't used by any agent) ------------------------------------------------------------

    @Override
    public List<WorkItem> search(String profile, String query) {
        throw new UnsupportedOperationException("RemoteBoardPort does not support search; no agent calls it");
    }

    @Override
    public WorkItem createItem(String profile, String kind, Map<String, Object> fields, String parentBoardId) {
        throw new UnsupportedOperationException("RemoteBoardPort is read-only; writes go through BoardSideEffects");
    }

    @Override
    public void updateFields(WorkItemRef ref, Map<String, Object> fields) {
        throw new UnsupportedOperationException("RemoteBoardPort is read-only; writes go through BoardSideEffects");
    }

    @Override
    public void transition(WorkItemRef ref, CanonicalState state) {
        throw new UnsupportedOperationException("RemoteBoardPort is read-only; writes go through BoardSideEffects");
    }

    @Override
    public CommentRef addComment(WorkItemRef ref, String body, String author) {
        throw new UnsupportedOperationException("RemoteBoardPort is read-only; writes go through BoardSideEffects");
    }

    @Override
    public void link(WorkItemRef ref, String otherBoardId, String relation) {
        throw new UnsupportedOperationException("RemoteBoardPort is read-only; writes go through BoardSideEffects");
    }

    @Override
    public AttachmentRef attach(WorkItemRef ref, String fileName, byte[] content) {
        throw new UnsupportedOperationException("RemoteBoardPort is read-only; writes go through BoardSideEffects");
    }

    @Override
    public CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent) {
        throw new UnsupportedOperationException("RemoteBoardPort is read-only; webhook ingress is control-plane's job");
    }
}
