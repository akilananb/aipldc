package ai.pdlc.adapters.remoteboard;

import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for a real bug found running the pilot with a real LLM (not the stub-llm
 * gateway): agents' own in-memory board started empty (separate JVM from control-plane), so
 * grill/PO prompts only ever saw the board id, never the title/description. Verifies {@link
 * RemoteBoardPort} against a fake HTTP server standing in for control-plane's board-proxy API -
 * NOT {@code /api/items}, which is backed by a Postgres row that doesn't exist yet at the point
 * {@code grillEvaluate} (the very first read) runs.
 */
class RemoteBoardPortTest {

    private HttpServer server;
    private RemoteBoardPort port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/board/local/5100", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/comments")) {
                respond(exchange, 200, """
                        [{"id":"c1","by":"po@acme","role":"PO","stage":"grill","target":"note","text":"q1: answer","intent":"NOTE","blocking":false,"version":0}]
                        """);
            } else {
                respond(exchange, 200, """
                        {"id":"5100","kind":"feature","title":"Add a todo list","description":"Track follow-up actions on orders.","state":"needs-clarification","parentId":null,"areaPath":null,"comments":[]}
                        """);
            }
        });
        server.createContext("/api/board/local/nonexistent", exchange ->
                respond(exchange, 404, "{\"error\":\"No work item found\"}"));
        server.start();
        port = new RemoteBoardPort("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void getItemReturnsRealTitleAndDescriptionAcrossTheProcessBoundary() {
        WorkItem item = port.getItem(new WorkItemRef("local", "5100"));

        assertThat(item.title()).isEqualTo("Add a todo list");
        assertThat(item.description()).isEqualTo("Track follow-up actions on orders.");
        assertThat(item.state()).isEqualTo(CanonicalState.NEEDS_CLARIFICATION);
    }

    @Test
    void listCommentsReturnsRealBoardComments() {
        List<Comment> comments = port.listComments(new WorkItemRef("local", "5100"));

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).text()).isEqualTo("q1: answer");
        assertThat(comments.get(0).by()).isEqualTo("po@acme");
    }

    @Test
    void getItemForUnknownBoardIdThrows() {
        assertThatThrownBy(() -> port.getItem(new WorkItemRef("local", "nonexistent")))
                .isInstanceOf(RemoteBoardPortException.class)
                .hasMessageContaining("404");
    }

    @Test
    void writesAreUnsupported() {
        WorkItemRef ref = new WorkItemRef("local", "5100");
        assertThatThrownBy(() -> port.transition(ref, CanonicalState.APPROVED))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> port.addComment(ref, "text", "author"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
