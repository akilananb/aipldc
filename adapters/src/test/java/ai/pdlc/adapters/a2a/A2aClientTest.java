package ai.pdlc.adapters.a2a;

import ai.pdlc.adapters.mcp.McpAuth;
import ai.pdlc.core.platform.EgressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aClientTest {

    private final EgressPolicy egress = new EgressPolicy(Set.of("localhost"));
    private final A2aClient client = new A2aClient(egress::check);
    private final Duration timeout = Duration.ofSeconds(3);
    private TestA2aServer server;

    @BeforeEach
    void start() throws Exception {
        server = new TestA2aServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private A2aClient.Session session(A2aClient.Dialect dialect, String skill) {
        server.dialect = dialect;
        A2aClient.Card card = client.card(server.origin(), McpAuth.NONE, timeout, 64_000);
        return client.open(card, McpAuth.NONE, timeout, 64_000, skill);
    }

    @ParameterizedTest
    @EnumSource(A2aClient.Dialect.class)
    void readsTheCardAndPicksTheDialect(A2aClient.Dialect dialect) {
        server.dialect = dialect;

        A2aClient.Card card = client.card(server.origin(), McpAuth.NONE, timeout, 64_000);

        assertThat(card.dialect()).isEqualTo(dialect);
        assertThat(card.endpoint()).isEqualTo(server.endpoint());
        assertThat(card.streaming()).isTrue();
        assertThat(card.offers("ask")).isTrue();
        assertThat(card.offers("wipe")).isFalse();
        assertThat(card.name()).isEqualTo("Test partner agent");
    }

    @Test
    void fallsBackToTheLegacyCardPath() {
        server.dialect = A2aClient.Dialect.V0_3;
        server.legacyCardPathOnly = true;

        assertThat(client.card(server.origin(), McpAuth.NONE, timeout, 64_000).dialect()).isEqualTo(A2aClient.Dialect.V0_3);
    }

    @Test
    void theCardIsNotTrustedToPointElsewhere() {
        server.cardEndpointOverride = "http://evil.example:8080/a2a";

        assertThatThrownBy(() -> client.card(server.origin(), McpAuth.NONE, timeout, 64_000))
                .isInstanceOf(A2aException.class).hasMessageContaining("not the approved origin");
    }

    @Test
    void theCardIsFetchedThroughTheEgressPolicy() {
        A2aClient strict = new A2aClient(new EgressPolicy(Set.of())::check);

        assertThatThrownBy(() -> strict.card(server.origin(), McpAuth.NONE, timeout, 64_000))
                .isInstanceOf(A2aException.class).hasMessageContaining("destination not allowed");
    }

    @ParameterizedTest
    @EnumSource(A2aClient.Dialect.class)
    void sendsAMessageAndGetsACompletedTask(A2aClient.Dialect dialect) {
        A2aClient.Task task = session(dialect, "echo").send("run-1:1", "summarise Q3", null, null);

        assertThat(task.state()).isEqualTo(A2aClient.State.COMPLETED);
        assertThat(task.id()).startsWith("task-");
        assertThat(task.outputText()).isEqualTo("echo: summarise Q3");
        assertThat(server.calls).containsExactly("send run-1:1 summarise Q3");
        assertThat(server.versions).last().isEqualTo(dialect == A2aClient.Dialect.V1_0 ? "1.0" : "0.3");
    }

    @ParameterizedTest
    @EnumSource(A2aClient.Dialect.class)
    void pollsALongTaskUntilItCompletes(A2aClient.Dialect dialect) {
        A2aClient.Session session = session(dialect, "slow");
        A2aClient.Task task = session.send("run-2:1", "go", null, null);
        assertThat(task.state()).isEqualTo(A2aClient.State.WORKING);

        assertThat(session.get(task.id()).state()).isEqualTo(A2aClient.State.WORKING);
        A2aClient.Task done = session.get(task.id());

        assertThat(done.state()).isEqualTo(A2aClient.State.COMPLETED);
        assertThat(done.outputText()).isEqualTo("slow result");
    }

    @ParameterizedTest
    @EnumSource(A2aClient.Dialect.class)
    void inputRequiredCarriesTheQuestionAndAFollowUpContinuesTheSameTask(A2aClient.Dialect dialect) {
        A2aClient.Session session = session(dialect, "ask");
        A2aClient.Task task = session.send("run-3:1", "build the report", null, null);
        assertThat(task.state()).isEqualTo(A2aClient.State.INPUT_REQUIRED);
        assertThat(task.state().interrupted()).isTrue();
        assertThat(task.statusText()).isEqualTo("Which region should the report cover?");

        A2aClient.Task done = session.send("run-3:2", "EMEA", task.id(), task.contextId());

        assertThat(done.id()).isEqualTo(task.id());
        assertThat(done.state()).isEqualTo(A2aClient.State.COMPLETED);
        assertThat(done.outputText()).isEqualTo("thanks: EMEA");
    }

    @ParameterizedTest
    @EnumSource(A2aClient.Dialect.class)
    void reportsRemoteFailureAndAuthRequired(A2aClient.Dialect dialect) {
        A2aClient.Task failed = session(dialect, "fail").send("run-4:1", "x", null, null);
        A2aClient.Task login = session(dialect, "login").send("run-5:1", "x", null, null);

        assertThat(failed.state()).isEqualTo(A2aClient.State.FAILED);
        assertThat(failed.statusText()).isEqualTo("the partner system is down");
        assertThat(login.state()).isEqualTo(A2aClient.State.AUTH_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(A2aClient.Dialect.class)
    void cancelReportsWhatTheAgentDid(A2aClient.Dialect dialect) {
        A2aClient.Session hold = session(dialect, "hold");
        A2aClient.Task held = hold.send("run-6:1", "x", null, null);
        A2aClient.Task pinned = session(dialect, "pinned").send("run-7:1", "x", null, null);

        assertThat(hold.cancel(held.id()).ack()).isEqualTo(A2aClient.CancelAck.ACKNOWLEDGED);
        assertThat(hold.cancel(pinned.id()).ack()).isEqualTo(A2aClient.CancelAck.REFUSED);
        assertThat(hold.cancel("task-nope").ack()).isEqualTo(A2aClient.CancelAck.NOT_FOUND);
        assertThat(server.state(pinned.id())).containsIgnoringCase("working");
    }

    @ParameterizedTest
    @EnumSource(A2aClient.Dialect.class)
    void streamsUpdatesUntilTheTaskFinishes(A2aClient.Dialect dialect) {
        List<A2aClient.State> seen = new ArrayList<>();

        A2aClient.Task task = session(dialect, "echo").stream("run-8:1", "stream me", null, null, timeout, t -> seen.add(t.state()));

        assertThat(task.state()).isEqualTo(A2aClient.State.COMPLETED);
        assertThat(task.outputText()).isEqualTo("echo: stream me");
        assertThat(seen).first().isEqualTo(A2aClient.State.WORKING);
        assertThat(seen).last().isEqualTo(A2aClient.State.COMPLETED);
    }

    @Test
    void aResendWithTheSameMessageIdIsNotANewTask() {
        A2aClient.Session session = session(A2aClient.Dialect.V1_0, "hold");

        A2aClient.Task first = session.send("run-9:1", "x", null, null);
        A2aClient.Task again = session.send("run-9:1", "x", null, null);

        assertThat(again.id()).isEqualTo(first.id());
    }

    @Test
    void sendsTheConnectionsBearer() {
        server.requiredBearer = "partner-token";
        A2aClient.Card card = client.card(server.origin(), McpAuth.NONE, timeout, 64_000);

        assertThatThrownBy(() -> client.open(card, McpAuth.NONE, timeout, 64_000, "echo").send("m", "x", null, null))
                .isInstanceOf(A2aException.class).hasMessageContaining("401");
        assertThat(client.open(card, McpAuth.bearer("partner-token"), timeout, 64_000, "echo").send("m2", "x", null, null).state())
                .isEqualTo(A2aClient.State.COMPLETED);
    }

    @Test
    void capsResponses() {
        server.dialect = A2aClient.Dialect.V1_0;

        assertThatThrownBy(() -> client.card(server.origin(), McpAuth.NONE, timeout, 50))
                .isInstanceOf(A2aException.class).hasMessageContaining("exceeded 50 bytes");
    }

    @Test
    void doesNotFollowRedirectsAndReportsAMaybeSentTimeout() throws Exception {
        HttpServer odd = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        odd.createContext("/.well-known/agent-card.json", ex -> {
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        odd.createContext("/a2a", ex -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        odd.start();
        try {
            URI origin = URI.create("http://localhost:" + odd.getAddress().getPort());
            assertThatThrownBy(() -> client.card(origin, McpAuth.NONE, timeout, 64_000))
                    .isInstanceOf(A2aException.class).hasMessageContaining("redirects are not followed");

            A2aClient.Card card = new A2aClient.Card("odd", URI.create(origin + "/a2a"), A2aClient.Dialect.V1_0, null, false, List.of());
            assertThatThrownBy(() -> client.open(card, McpAuth.NONE, Duration.ofMillis(300), 64_000, null).send("m", "x", null, null))
                    .isInstanceOfSatisfying(A2aException.class, e -> {
                        assertThat(e.maybeSent()).isTrue();
                        assertThat(e.getMessage()).contains("timed out");
                    });
            assertThatThrownBy(() -> client.open(card, McpAuth.NONE, Duration.ofMillis(300), 64_000, null).get("t"))
                    .isInstanceOfSatisfying(A2aException.class, e -> assertThat(e.maybeSent()).isFalse());
        } finally {
            odd.stop(0);
        }
    }
}
