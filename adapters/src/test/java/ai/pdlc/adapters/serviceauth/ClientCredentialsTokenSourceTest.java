package ai.pdlc.adapters.serviceauth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientCredentialsTokenSourceTest {

    private HttpServer server;
    private final AtomicInteger issued = new AtomicInteger();
    private final List<String> authorizations = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = ("{\"access_token\":\"tok-" + issued.incrementAndGet() + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/token";
    }

    @Test
    void requestsATokenWithBasicClientAuthAndCachesItUntilNearExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var source = new ClientCredentialsTokenSource(url(), "agents", "s3cret", "pdlc.board.read", HttpClient.newHttpClient(), clock);

        assertThat(source.token()).isEqualTo("tok-1");
        assertThat(source.token()).isEqualTo("tok-1");
        clock.now = clock.now.plusSeconds(239);
        assertThat(source.token()).isEqualTo("tok-1");
        clock.now = clock.now.plusSeconds(1);
        assertThat(source.token()).isEqualTo("tok-2");

        assertThat(authorizations.get(0)).isEqualTo("Basic " + Base64.getEncoder().encodeToString("agents:s3cret".getBytes(StandardCharsets.UTF_8)));
        assertThat(bodies.get(0)).isEqualTo("grant_type=client_credentials&scope=pdlc.board.read");
    }

    @Test
    void aFailedTokenRequestIsAnErrorNotAnEmptyToken() {
        status = 401;
        var source = new ClientCredentialsTokenSource(url(), "agents", "wrong", null);

        assertThatThrownBy(source::token).isInstanceOf(IllegalStateException.class).hasMessageContaining("401");
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
