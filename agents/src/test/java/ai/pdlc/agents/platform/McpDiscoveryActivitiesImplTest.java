package ai.pdlc.agents.platform;

import ai.pdlc.adapters.mcp.TestMcpServer;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.platform.McpFingerprint;
import ai.pdlc.core.port.SecretsPort;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpDiscoveryActivitiesImplTest {

    private TestMcpServer server;
    private final ToolStore store = mock(ToolStore.class);
    private final SecretsPort secrets = ref -> "kv://orders-mcp-secret".equals(ref) ? "cs-secret-42" : null;
    private final EgressPolicy egress = new EgressPolicy(Set.of("localhost"));
    private final McpDiscoveryActivitiesImpl activities = new McpDiscoveryActivitiesImpl(store,
            new McpToolCaller(egress, new McpCredentials(secrets, egress)));

    @BeforeEach
    void start() throws Exception {
        server = new TestMcpServer();
        server.requireOAuth = true;
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void connection(String status, String secretRef) {
        when(store.connection("orders-mcp")).thenReturn(Optional.of(new ToolStore.ConnectionInfo("orders-mcp", "MCP_SERVER", status,
                null, "OAUTH_CLIENT_CREDENTIALS", secretRef, server.endpoint().toString(), "platform-client")));
    }

    @Test
    void listsEveryToolWithItsFingerprintUsingClientCredentials() {
        connection("ACTIVE", "kv://orders-mcp-secret");

        var result = activities.listTools("orders-mcp");

        assertThat(result.error()).isNull();
        assertThat(result.tools()).extracting(t -> t.name()).containsExactly("lookup_order", "cancel_order");
        var lookup = result.tools().get(0);
        assertThat(lookup.fingerprint()).isEqualTo(McpFingerprint.of(lookup.name(), lookup.description(), lookup.inputSchema(),
                lookup.annotations()));
        assertThat(server.tokenRequests).hasValue(1);
    }

    @Test
    void aRevokedConnectionOrAnUnresolvableSecretIsReportedWithoutContactingTheServer() {
        connection("REVOKED", "kv://orders-mcp-secret");
        assertThat(activities.listTools("orders-mcp").error()).contains("revoked");

        connection("ACTIVE", "kv://missing");
        // A missing secret is configuration, not a transient fault: reported, not retried.
        assertThat(activities.listTools("orders-mcp").error())
                .isEqualTo("the credential for connection orders-mcp could not be resolved");
        assertThat(server.calls).isEmpty();
        assertThat(server.tokenRequests).hasValue(0);
    }

    @Test
    void anUnreachableServerIsARetryableFailure() {
        when(store.connection("orders-mcp")).thenReturn(Optional.of(new ToolStore.ConnectionInfo("orders-mcp", "MCP_SERVER",
                "ACTIVE", null, "NONE", null, "http://localhost:1/mcp", null)));

        assertThatThrownBy(() -> activities.listTools("orders-mcp")).isInstanceOf(ApplicationFailure.class)
                .satisfies(e -> assertThat(((ApplicationFailure) e).isNonRetryable()).isFalse());
    }
}
