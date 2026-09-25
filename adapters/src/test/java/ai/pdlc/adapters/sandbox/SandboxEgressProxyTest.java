package ai.pdlc.adapters.sandbox;

import ai.pdlc.core.platform.EgressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxEgressProxyTest {

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-01T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final List<SandboxEgressProxy.Event> events = new CopyOnWriteArrayList<>();
    private final List<String> upstreamRequests = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private SandboxEgressProxy proxy;
    private String upstreamHost;

    @BeforeEach
    void start() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            upstreamRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " proxy-auth=" + exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
            byte[] body = "hello from upstream".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        upstreamHost = "localhost:" + upstream.getAddress().getPort();
        // localhost is the enterprise-allowlisted "public" destination here; 127.0.0.1 stays private (blocked).
        EgressPolicy egress = new EgressPolicy(Set.of("localhost"));
        proxy = new SandboxEgressProxy("127.0.0.1", 0, egress::check, events::add, clock);
    }

    @AfterEach
    void stop() throws IOException {
        proxy.close();
        upstream.stop(0);
    }

    private String send(String request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", proxy.port())) {
            s.setSoTimeout(5_000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String auth(String token) {
        return "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(("pdlc:" + token).getBytes(StandardCharsets.UTF_8)) + "\r\n";
    }

    private String get(String target, String token) throws IOException {
        return send("GET " + target + " HTTP/1.1\r\nHost: " + upstreamHost + "\r\n" + (token == null ? "" : auth(token)) + "\r\n");
    }

    private SandboxEgressProxy.Grant grant(String... hosts) {
        return proxy.issue("run-1", "run-1:1:call-a", Set.of(hosts), Duration.ofMinutes(2));
    }

    @Test
    void forwardsAnApprovedHostWithALiveCredentialAndStripsTheProxyHeaders() throws IOException {
        SandboxEgressProxy.Grant grant = grant(upstreamHost);

        String response = get("http://" + upstreamHost + "/orders?id=42", grant.token());

        assertThat(response).startsWith("HTTP/1.1 200").contains("hello from upstream");
        assertThat(upstreamRequests).containsExactly("GET /orders?id=42 proxy-auth=null");
        assertThat(events).containsExactly(new SandboxEgressProxy.Event("run-1:1:call-a", "localhost",
                upstream.getAddress().getPort(), true, null));
    }

    @Test
    void refusesMissingWrongRevokedAndExpiredCredentials() throws IOException {
        String target = "http://" + upstreamHost + "/";
        SandboxEgressProxy.Grant revoked = grant(upstreamHost);
        proxy.revoke(revoked.token());
        SandboxEgressProxy.Grant cancelled = grant(upstreamHost);
        proxy.revokeRun("run-1");
        SandboxEgressProxy.Grant expired = proxy.issue("run-2", "run-2:1:c", Set.of(upstreamHost), Duration.ofSeconds(30));
        clock.now = clock.now.plusSeconds(31);

        assertThat(get(target, null)).startsWith("HTTP/1.1 407");
        assertThat(get(target, "not-a-token")).startsWith("HTTP/1.1 407");
        assertThat(get(target, revoked.token())).startsWith("HTTP/1.1 407");
        assertThat(get(target, cancelled.token())).startsWith("HTTP/1.1 407");
        assertThat(get(target, expired.token())).startsWith("HTTP/1.1 407");
        assertThat(proxy.isLive(expired.token())).isFalse();
        assertThat(upstreamRequests).isEmpty();
    }

    @Test
    void refusesAHostThePackageWasNotApprovedFor() throws IOException {
        SandboxEgressProxy.Grant grant = grant("api.example.com");

        String response = get("http://" + upstreamHost + "/", grant.token());

        assertThat(response).startsWith("HTTP/1.1 403").contains("not approved");
        assertThat(upstreamRequests).isEmpty();
        assertThat(events).singleElement().satisfies(e -> assertThat(e.allowed()).isFalse());
    }

    @Test
    void appliesTheEgressPolicyEvenToAnApprovedHost() throws IOException {
        String privateHost = "127.0.0.1:" + upstream.getAddress().getPort();
        SandboxEgressProxy.Grant grant = grant(privateHost);

        String response = get("http://" + privateHost + "/", grant.token());

        assertThat(response).startsWith("HTTP/1.1 403").contains("destination not allowed");
        assertThat(upstreamRequests).isEmpty();
    }

    @Test
    void refusesNonHttpSchemes() throws IOException {
        SandboxEgressProxy.Grant grant = grant(upstreamHost);

        assertThat(get("ftp://" + upstreamHost + "/", grant.token())).startsWith("HTTP/1.1 403");
    }

    @Test
    void tunnelsConnectToAnApprovedHost() throws IOException {
        SandboxEgressProxy.Grant grant = grant(upstreamHost);

        try (Socket s = new Socket("127.0.0.1", proxy.port())) {
            s.setSoTimeout(5_000);
            s.getOutputStream().write(("CONNECT " + upstreamHost + " HTTP/1.1\r\nHost: " + upstreamHost + "\r\n" + auth(grant.token())
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            String established = readLine(in);
            assertThat(established).startsWith("HTTP/1.1 200");
            assertThat(readLine(in)).isEmpty();
            // Plain HTTP inside the tunnel stands in for TLS: the proxy only relays bytes.
            s.getOutputStream().write(("GET /tunnelled HTTP/1.1\r\nHost: " + upstreamHost + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).startsWith("HTTP/1.1 200").contains("hello from upstream");
        }
        assertThat(upstreamRequests).containsExactly("GET /tunnelled proxy-auth=null");
    }

    @Test
    void theGrantNeverPrintsItsToken() {
        SandboxEgressProxy.Grant grant = grant(upstreamHost);

        assertThat(grant.toString()).doesNotContain(grant.token());
        assertThat(SandboxEgressProxy.proxyUrl(grant, "relay", 3128)).isEqualTo("http://pdlc:" + grant.token() + "@relay:3128");
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return line.toString();
    }
}
