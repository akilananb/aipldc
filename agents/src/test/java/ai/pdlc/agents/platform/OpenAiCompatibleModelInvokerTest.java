package ai.pdlc.agents.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP through Spring AI's OpenAI client to a local OpenAI-compatible chat-completions stub. */
class OpenAiCompatibleModelInvokerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private volatile String usage = ",\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4,\"total_tokens\":15}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            bodies.add(body);
            String content = "echo: " + body.get("messages").get(0).get("content").asText();
            String response = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\""
                    + body.get("model").asText() + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                    + JSON.writeValueAsString(content) + "},\"finish_reason\":\"stop\"}]" + usage + "}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ModelInvoker.Endpoint endpoint() {
        return new ModelInvoker.Endpoint("http://localhost:" + server.getAddress().getPort() + "/v1", "sk-test",
                "anthropic/claude-sonnet-4");
    }

    @Test
    void callsTheConnectionsEndpointWithItsKeyModelAndTokenLimit() {
        ModelInvoker.Reply reply = new OpenAiCompatibleModelInvoker().call(endpoint(), "Return ALPHA", 64, Duration.ofSeconds(10));

        assertThat(reply.text()).isEqualTo("echo: Return ALPHA");
        assertThat(reply.promptTokens()).isEqualTo(11);
        assertThat(reply.completionTokens()).isEqualTo(4);
        assertThat(authorizations).containsExactly("Bearer sk-test");
        assertThat(bodies.get(0).get("model").asText()).isEqualTo("anthropic/claude-sonnet-4");
        JsonNode limit = bodies.get(0).has("max_tokens") ? bodies.get(0).get("max_tokens") : bodies.get(0).get("max_completion_tokens");
        assertThat(limit.asInt()).isEqualTo(64);
    }

    @Test
    void missingUsageIsReportedAsUnknownNotZero() {
        usage = "";

        ModelInvoker.Reply reply = new OpenAiCompatibleModelInvoker().call(endpoint(), "hi", null, Duration.ofSeconds(10));

        assertThat(reply.promptTokens()).isNull();
        assertThat(reply.completionTokens()).isNull();
    }
}
