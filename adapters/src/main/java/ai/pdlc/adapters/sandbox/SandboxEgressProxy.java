package ai.pdlc.adapters.sandbox;

import ai.pdlc.core.platform.EgressPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only network path out of a sandbox (docs/phase-2-execution-spec.md slice 2.4): an HTTP
 * forward proxy (absolute-form requests and {@code CONNECT} tunnels) that admits a request only
 * with a live, per-call credential and only to that call's approved hosts, then applies the
 * {@link EgressPolicy} and connects to the address it checked (so DNS cannot be rebound between
 * the check and the connection).
 *
 * <p>The credential is the sandbox's short-lived credential: {@link #issue} mints it for one call
 * with a time limit, and {@link #revoke}/{@link #revokeRun} end it the moment the call finishes, is
 * cancelled or is killed - a package (or anything that copied its environment) is locked out from
 * then on. Tokens are random, compared exactly, and never logged.
 */
public final class SandboxEgressProxy implements AutoCloseable {

    /** One decision, for the run trace and tests. {@code allowed} false carries the reason. */
    public record Event(String callKey, String host, int port, boolean allowed, String reason) {
    }

    /** A minted credential: send it as the proxy password (user {@code pdlc}). */
    public record Grant(String token, String runId, String callKey, Set<String> hosts, Instant expiresAt) {
        @Override
        public String toString() {
            return "Grant[callKey=" + callKey + ", hosts=" + hosts + ", expiresAt=" + expiresAt + "]";
        }
    }

    private static final int MAX_HEADER_BYTES = 16_384;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerSocket server;
    private final Function<URI, EgressPolicy.Decision> guard;
    private final Consumer<Event> events;
    private final Clock clock;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public SandboxEgressProxy(String bindHost, int port, Function<URI, EgressPolicy.Decision> guard, Consumer<Event> events)
            throws IOException {
        this(bindHost, port, guard, events, Clock.systemUTC());
    }

    SandboxEgressProxy(String bindHost, int port, Function<URI, EgressPolicy.Decision> guard, Consumer<Event> events,
                       Clock clock) throws IOException {
        this.guard = guard;
        this.events = events;
        this.clock = clock;
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(bindHost, port));
        Thread.ofVirtual().name("sandbox-egress-proxy").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Mints the credential for one sandbox call, allowed to reach only {@code hosts} ({@code host} or {@code host:port}). */
    public Grant issue(String runId, String callKey, Set<String> hosts, Duration ttl) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        Grant grant = new Grant(HexFormat.of().formatHex(bytes), runId, callKey,
                hosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()),
                clock.instant().plus(ttl));
        grants.put(grant.token(), grant);
        return grant;
    }

    public void revoke(String token) {
        grants.remove(token);
    }

    /** Ends every credential of a run (cancellation). */
    public void revokeRun(String runId) {
        grants.values().removeIf(g -> g.runId().equals(runId));
    }

    public boolean isLive(String token) {
        Grant g = grants.get(token);
        return g != null && clock.instant().isBefore(g.expiresAt());
    }

    /** The proxy URL a sandbox is given: {@code http://pdlc:<token>@<host>:<port>}. */
    public static String proxyUrl(Grant grant, String advertisedHost, int advertisedPort) {
        return "http://pdlc:" + grant.token() + "@" + advertisedHost + ":" + advertisedPort;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().start(() -> handle(client));
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(30_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String head = readHead(in);
            if (head == null) {
                return;
            }
            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length != 3) {
                respond(out, 400, "bad request");
                return;
            }
            Grant grant = authenticate(lines);
            if (grant == null) {
                out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"pdlc-sandbox\"\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                return;
            }
            boolean connect = "CONNECT".equalsIgnoreCase(requestLine[0]);
            URI target;
            try {
                target = connect ? URI.create("https://" + requestLine[1]) : URI.create(requestLine[1]);
            } catch (IllegalArgumentException e) {
                respond(out, 400, "bad target");
                return;
            }
            String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
            int port = target.getPort() != -1 ? target.getPort() : "https".equals(target.getScheme()) ? 443 : 80;
            if (!connect && !"http".equals(target.getScheme())) {
                deny(out, grant, host, port, "only http requests and CONNECT tunnels are proxied");
                return;
            }
            if (!grant.hosts().contains(host) && !grant.hosts().contains(host + ":" + port)) {
                deny(out, grant, host, port, "host " + host + " is not approved for this package");
                return;
            }
            EgressPolicy.Decision decision = guard.apply(URI.create((connect ? "https" : "http") + "://" + host + ":" + port + "/"));
            if (!decision.allowed() || decision.addresses().isEmpty()) {
                deny(out, grant, host, port, "destination not allowed: " + decision.reason());
                return;
            }
            InetAddress address = decision.addresses().get(0);
            try (Socket upstream = new Socket()) {
                upstream.connect(new InetSocketAddress(address, port), 10_000);
                upstream.setSoTimeout(60_000);
                events.accept(new Event(grant.callKey(), host, port, true, null));
                if (connect) {
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else {
                    upstream.getOutputStream().write(originForm(lines, target).getBytes(StandardCharsets.ISO_8859_1));
                }
                relay(client, upstream);
            } catch (IOException e) {
                events.accept(new Event(grant.callKey(), host, port, false, "upstream unreachable"));
                respond(out, 502, "upstream unreachable");
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    private Grant authenticate(String[] lines) {
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Proxy-Authorization")) {
                String value = line.substring(colon + 1).trim();
                if (!value.regionMatches(true, 0, "Basic ", 0, 6)) {
                    return null;
                }
                String decoded;
                try {
                    decoded = new String(Base64.getDecoder().decode(value.substring(6).trim()), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
                int sep = decoded.indexOf(':');
                String token = sep < 0 ? "" : decoded.substring(sep + 1);
                Grant grant = grants.get(token);
                return grant != null && clock.instant().isBefore(grant.expiresAt()) ? grant : null;
            }
        }
        return null;
    }

    /** The request as the origin should see it: origin-form path, no proxy headers, one request per connection. */
    private static String originForm(String[] lines, URI target) {
        String[] requestLine = lines[0].split(" ");
        String path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();
        if (target.getRawQuery() != null) {
            path += "?" + target.getRawQuery();
        }
        StringBuilder request = new StringBuilder(requestLine[0]).append(' ').append(path).append(' ').append(requestLine[2]).append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String name = lines[i].contains(":") ? lines[i].substring(0, lines[i].indexOf(':')).trim().toLowerCase(Locale.ROOT) : "";
            if (!name.startsWith("proxy-") && !name.equals("connection") && !name.equals("keep-alive")) {
                request.append(lines[i]).append("\r\n");
            }
        }
        return request.append("Connection: close\r\n\r\n").toString();
    }

    private void deny(OutputStream out, Grant grant, String host, int port, String reason) throws IOException {
        events.accept(new Event(grant.callKey(), host, port, false, reason));
        respond(out, 403, "denied by policy: " + reason);
    }

    private static void respond(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " " + (status == 403 ? "Forbidden" : status == 502 ? "Bad Gateway" : "Bad Request")
                + "\r\nContent-Type: text/plain\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** Reads the request head (up to the blank line) byte by byte so no body bytes are consumed. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (head.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) {
                String text = head.toString(StandardCharsets.ISO_8859_1);
                return text.substring(0, text.length() - 4);
            }
        }
        return null;
    }

    private static void relay(Socket a, Socket b) throws IOException {
        Thread up = Thread.ofVirtual().start(() -> pipe(a, b));
        pipe(b, a);
        try {
            up.join(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pipe(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
            to.shutdownOutput();
        } catch (IOException ignored) {
            // either side closed
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        grants.clear();
        server.close();
    }
}
