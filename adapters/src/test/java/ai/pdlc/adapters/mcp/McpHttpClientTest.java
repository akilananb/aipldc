package ai.pdlc.adapters.mcp;

import ai.pdlc.core.platform.EgressPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpHttpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final EgressPolicy egress = new EgressPolicy(Set.of("localhost"));
    private final McpHttpClient client = new McpHttpClient(egress::check);
    private TestMcpServer server;

    @BeforeEach
    void start() throws Exception {
        server = new TestMcpServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private McpHttpClient.Session open(McpAuth auth) {
        return client.open(server.endpoint(), auth, Duration.ofSeconds(2), 64_000);
    }

    @Test
    void initializesListsEveryPageAndCallsATool() throws Exception {
        McpHttpClient.Session session = open(McpAuth.bearer("static-token"));

        var tools = session.listTools();
        var result = session.callTool("lookup_order", JSON.readTree("{\"orderId\":\"42\"}"));

        assertThat(tools).extracting(McpHttpClient.Tool::name).containsExactly("lookup_order", "cancel_order");
        assertThat(tools.get(0).annotations()).containsEntry("readOnlyHint", true);
        assertThat(tools.get(0).inputSchema()).containsEntry("type", "object");
        assertThat(result.text()).startsWith("order 42 is shipped");
        assertThat(result.isError()).isFalse();
        assertThat(server.calls).containsExactly("lookup_order {\"orderId\":\"42\"}");
        assertThat(server.authorizations).allMatch("Bearer static-token"::equals);
    }

    @Test
    void readsEventStreamResponsesSkippingServerNotifications() {
        server.sse = true;

        assertThat(open(McpAuth.NONE).listTools()).hasSize(2);
    }

    @Test
    void aServerErrorFlagIsReturnedNotThrown() throws Exception {
        assertThat(open(McpAuth.NONE).callTool("boom", JSON.readTree("{}")).isError()).isTrue();
    }

    @Test
    void refusedCredentialsAndBlockedDestinationsFailWithoutLeakingAnything() {
        server.requiredBearer = "right";

        assertThatThrownBy(() -> open(McpAuth.bearer("wrong-secret-value")))
                .isInstanceOf(McpException.class).hasMessageContaining("401").hasMessageNotContaining("wrong-secret-value");
        assertThatThrownBy(() -> client.open(URI.create("http://169.254.169.254/mcp"), McpAuth.NONE, Duration.ofSeconds(2), 1000))
                .hasMessageContaining("destination not allowed");
        assertThatThrownBy(() -> client.open(URI.create(server.base() + "/redirect"), McpAuth.NONE, Duration.ofSeconds(2), 1000))
                .hasMessageContaining("redirects are not followed");
    }

    @Test
    void aStalledCallTimesOutAndReportsThatItMayHaveBeenSent() throws Exception {
        McpHttpClient.Session session = client.open(server.endpoint(), McpAuth.NONE, Duration.ofSeconds(1), 64_000);

        assertThatThrownBy(() -> session.callTool("slow_order", JSON.readTree("{\"orderId\":\"1\"}")))
                .isInstanceOfSatisfying(McpException.class, e -> assertThat(e.maybeSent()).isTrue())
                .hasMessageContaining("timed out");
    }

    @Test
    void oversizedResponsesAreRefused() {
        assertThatThrownBy(() -> client.open(server.endpoint(), McpAuth.NONE, Duration.ofSeconds(2), 50).listTools())
                .hasMessageContaining("exceeded 50 bytes");
    }

    @Test
    void clientCredentialsAreDiscoveredFetchedOnceAndCached() {
        server.requireOAuth = true;
        McpOAuth oauth = new McpOAuth(server.endpoint(), "platform-client", () -> "cs-secret-42", egress::check, Duration.ofSeconds(2));

        open(oauth).listTools();
        open(oauth).listTools();

        assertThat(server.tokenRequests).hasValue(1);
        assertThat(server.authorizations).contains("Bearer at-777");
    }

    @Test
    void aWrongClientSecretOrAForeignResourceIsRefused() {
        server.requireOAuth = true;
        McpOAuth wrong = new McpOAuth(server.endpoint(), "platform-client", () -> "nope-secret", egress::check, Duration.ofSeconds(2));
        assertThatThrownBy(() -> open(wrong)).hasMessageContaining("token request was refused").hasMessageNotContaining("nope-secret");

        assertThat(McpOAuth.sameResource(URI.create("https://mcp.example/mcp"), URI.create("https://mcp.example/mcp/"))).isTrue();
        assertThat(McpOAuth.sameResource(URI.create("https://mcp.example"), URI.create("https://mcp.example/mcp"))).isTrue();
        assertThat(McpOAuth.sameResource(URI.create("https://other.example/mcp"), URI.create("https://mcp.example/mcp"))).isFalse();
    }

    @Test
    void oauthMetadataOnABlockedAddressIsNeverFetched() {
        server.requireOAuth = true;
        EgressPolicy strict = new EgressPolicy(Set.of());
        // The MCP endpoint itself is allowed (localhost allowlisted); the metadata URL is not under this stricter guard.
        McpOAuth oauth = new McpOAuth(server.endpoint(), "platform-client", () -> "cs-secret-42", strict::check, Duration.ofSeconds(2));

        assertThatThrownBy(() -> open(oauth)).hasMessageContaining("OAuth destination not allowed");
        assertThat(server.tokenRequests).hasValue(0);
    }
}
