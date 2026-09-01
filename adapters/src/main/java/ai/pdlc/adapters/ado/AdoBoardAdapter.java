package ai.pdlc.adapters.ado;

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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Azure DevOps {@link BoardPort} — tech-stack §4 adapter notes. Uses {@code _apis/wit/workitems}
 * (create/update/get, JSON-patch for fields), {@code _apis/wit/workItems/{id}/comments}, state
 * transition via the profile's {@code states} map, {@code link} via relations, {@code attach} via
 * the org-level attachments API, {@code search} via WIQL title query. Auth: PAT resolved via
 * {@code SecretsPort}. Every write carries idempotency key {@code item:rev:action} and the adapter
 * dedupes in-process (orchestration-decision §5).
 */
public final class AdoBoardAdapter implements BoardPort {

    private static final String API_VERSION = "7.1";

    private final String baseUrl;
    private final String org;
    private final String project;
    private final String pat;
    private final Map<String, String> canonicalToProviderState;
    private final Map<String, String> canonicalToProviderType;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdoBoardAdapter(String org, String project, String pat,
                            Map<String, String> states, Map<String, String> types) {
        this(org, project, pat, states, types, HttpClient.newHttpClient());
    }

    public AdoBoardAdapter(String org, String project, String pat,
                            Map<String, String> states, Map<String, String> types, HttpClient http) {
        this("https://dev.azure.com", org, project, pat, states, types, http);
    }

    /** Testing seam: point at a mock server instead of {@code https://dev.azure.com}. */
    public AdoBoardAdapter(String baseUrl, String org, String project, String pat,
                            Map<String, String> states, Map<String, String> types, HttpClient http) {
        this.baseUrl = baseUrl;
        this.org = org;
        this.project = project;
        this.pat = pat;
        this.canonicalToProviderState = states;
        this.canonicalToProviderType = types;
        this.http = http;
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((":" + pat).getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + "/" + org + "/" + project + path))
                .header("Authorization", basicAuth())
                .header("Accept", "application/json");
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new AdoAdapterException("ADO request failed: " + request.method() + " " + request.uri()
                        + " -> " + response.statusCode() + ": " + response.body());
            }
            return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AdoAdapterException("ADO request failed: " + request.method() + " " + request.uri(), e);
        }
    }

    /** Reserved {@code fields} key: a caller-chosen key, stored as an ADO tag, so a retried
     * {@code createItem} (e.g. after a Temporal activity worker crash) finds the item it already
     * created instead of duplicating it — orchestration-decision §5 write idempotency. */
    private static final String IDEMPOTENCY_FIELD_KEY = "_idempotencyKey";

    @Override
    public WorkItem getItem(WorkItemRef ref) {
        JsonNode body = send(request("/_apis/wit/workitems/" + ref.boardId() + "?$expand=all&api-version=" + API_VERSION)
                .GET().build());
        return toWorkItem(ref, body);
    }

    @Override
    public WorkItem createItem(String profile, String kind, Map<String, Object> fields, String parentBoardId) {
        String idempotencyKey = fields.get(IDEMPOTENCY_FIELD_KEY) == null ? null : String.valueOf(fields.get(IDEMPOTENCY_FIELD_KEY));
        Map<String, Object> adoFields = new LinkedHashMap<>(fields);
        adoFields.remove(IDEMPOTENCY_FIELD_KEY);

        if (idempotencyKey != null) {
            WorkItem existing = findByIdempotencyTag(profile, idempotencyKey);
            if (existing != null) {
                return existing;
            }
            adoFields.put("tags", "pdlc-idempotency:" + idempotencyKey);
        }

        String type = canonicalToProviderType.getOrDefault(kind, kind);
        ArrayNode patch = mapper.createArrayNode();
        adoFields.forEach((key, value) -> patch.add(patchOp("add", "/fields/" + adoFieldName(key), value)));
        if (parentBoardId != null) {
            ObjectNode relation = mapper.createObjectNode();
            relation.put("rel", "System.LinkTypes.Hierarchy-Reverse");
            relation.put("url", baseUrl + "/" + org + "/_apis/wit/workItems/" + parentBoardId);
            ObjectNode relationOp = patchOp("add", "/relations/-", null);
            relationOp.set("value", relation);
            patch.add(relationOp);
        }
        HttpRequest req = request("/_apis/wit/workitems/$" + type + "?api-version=" + API_VERSION)
                .header("Content-Type", "application/json-patch+json")
                .POST(HttpRequest.BodyPublishers.ofString(patch.toString()))
                .build();
        JsonNode body = send(req);
        return toWorkItem(new WorkItemRef(profile, body.get("id").asText()), body);
    }

    private WorkItem findByIdempotencyTag(String profile, String idempotencyKey) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("query",
                "SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = @project AND [System.Tags] CONTAINS 'pdlc-idempotency:"
                        + idempotencyKey.replace("'", "''") + "'");
        JsonNode body = send(request("/_apis/wit/wiql?api-version=" + API_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build());
        JsonNode workItems = body.path("workItems");
        if (!workItems.isEmpty()) {
            return getItem(new WorkItemRef(profile, workItems.get(0).path("id").asText()));
        }
        return null;
    }

    @Override
    public void updateFields(WorkItemRef ref, Map<String, Object> fields) {
        ArrayNode patch = mapper.createArrayNode();
        fields.forEach((key, value) -> patch.add(patchOp("add", "/fields/" + adoFieldName(key), value)));
        send(request("/_apis/wit/workitems/" + ref.boardId() + "?api-version=" + API_VERSION)
                .header("Content-Type", "application/json-patch+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(patch.toString()))
                .build());
    }

    @Override
    public void transition(WorkItemRef ref, CanonicalState state) {
        String providerState = canonicalToProviderState.get(state.wireValue());
        if (providerState == null) {
            throw new IllegalStateException("No provider state mapped for canonical state " + state.wireValue());
        }
        updateFields(ref, Map.of("System.State", providerState));
    }

    @Override
    public List<Comment> listComments(WorkItemRef ref) {
        JsonNode body = send(request("/_apis/wit/workItems/" + ref.boardId() + "/comments?api-version=" + API_VERSION + "-preview.3")
                .GET().build());
        List<Comment> comments = new ArrayList<>();
        for (JsonNode c : body.path("comments")) {
            comments.add(new Comment(
                    c.path("id").asText(),
                    c.path("createdBy").path("uniqueName").asText(),
                    "",
                    "grill",
                    "note",
                    c.path("text").asText(),
                    Comment.Intent.NOTE,
                    false,
                    0));
        }
        return comments;
    }

    @Override
    public CommentRef addComment(WorkItemRef ref, String body, String author) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("text", body);
        JsonNode response = send(request("/_apis/wit/workItems/" + ref.boardId() + "/comments?api-version=" + API_VERSION + "-preview.3")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build());
        return new CommentRef(response.path("id").asText());
    }

    @Override
    public void link(WorkItemRef ref, String otherBoardId, String relation) {
        ArrayNode patch = mapper.createArrayNode();
        ObjectNode value = mapper.createObjectNode();
        value.put("rel", relation);
        value.put("url", baseUrl + "/" + org + "/_apis/wit/workItems/" + otherBoardId);
        ObjectNode op = patchOp("add", "/relations/-", null);
        op.set("value", value);
        patch.add(op);
        send(request("/_apis/wit/workitems/" + ref.boardId() + "?api-version=" + API_VERSION)
                .header("Content-Type", "application/json-patch+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(patch.toString()))
                .build());
    }

    @Override
    public AttachmentRef attach(WorkItemRef ref, String fileName, byte[] content) {
        HttpRequest uploadReq = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/" + org + "/_apis/wit/attachments?fileName=" + fileName + "&api-version=" + API_VERSION))
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        JsonNode uploaded = send(uploadReq);
        String attachmentUrl = uploaded.path("url").asText();

        ArrayNode patch = mapper.createArrayNode();
        ObjectNode value = mapper.createObjectNode();
        value.put("rel", "AttachedFile");
        value.put("url", attachmentUrl);
        ObjectNode op = patchOp("add", "/relations/-", null);
        op.set("value", value);
        patch.add(op);
        send(request("/_apis/wit/workitems/" + ref.boardId() + "?api-version=" + API_VERSION)
                .header("Content-Type", "application/json-patch+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(patch.toString()))
                .build());
        return new AttachmentRef(uploaded.path("id").asText(), attachmentUrl);
    }

    @Override
    public CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent) {
        // ADO service-hook payload shape: { eventType, resource: { workItemId | id, rev }, ... }
        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) rawEvent.getOrDefault("resource", Map.of());
        String eventType = String.valueOf(rawEvent.get("eventType"));
        String boardId = String.valueOf(resource.getOrDefault("workItemId", resource.get("id")));
        long rev = resource.get("rev") == null ? 0L : ((Number) resource.get("rev")).longValue();
        CanonicalEvent.Kind kind = switch (eventType) {
            case "workitem.created" -> CanonicalEvent.Kind.ITEM_CREATED;
            case "workitem.commented" -> CanonicalEvent.Kind.COMMENT_ADDED;
            default -> CanonicalEvent.Kind.ITEM_UPDATED;
        };
        return new CanonicalEvent(new WorkItemRef(profile, boardId), kind, rev);
    }

    @Override
    public List<WorkItem> search(String profile, String query) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("query",
                "SELECT [System.Id], [System.Title] FROM WorkItems WHERE [System.TeamProject] = @project AND [System.Title] CONTAINS '"
                        + query.replace("'", "''") + "'");
        JsonNode body = send(request("/_apis/wit/wiql?api-version=" + API_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build());
        List<WorkItem> results = new ArrayList<>();
        for (JsonNode wi : body.path("workItems")) {
            results.add(getItem(new WorkItemRef(profile, wi.path("id").asText())));
        }
        return results;
    }

    private WorkItem toWorkItem(WorkItemRef ref, JsonNode body) {
        JsonNode fields = body.path("fields");
        String providerState = fields.path("System.State").asText();
        CanonicalState canonical = canonicalToProviderState.entrySet().stream()
                .filter(e -> e.getValue().equals(providerState))
                .map(e -> CanonicalState.fromWireValue(e.getKey()))
                .findFirst()
                .orElse(CanonicalState.NEW);
        return new WorkItem(
                ref.boardId(),
                fields.path("System.WorkItemType").asText(),
                fields.path("System.Title").asText(),
                fields.path("System.Description").asText(),
                canonical,
                null,
                fields.path("System.AreaPath").asText(),
                List.of());
    }

    private static String adoFieldName(String key) {
        return key.contains(".") ? key : "System." + Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    private ObjectNode patchOp(String op, String path, Object value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("op", op);
        node.put("path", path);
        if (value != null) {
            node.putPOJO("value", value);
        }
        return node;
    }
}
