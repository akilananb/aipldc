package ai.pdlc.adapters.mcp;

import ai.pdlc.core.platform.EgressPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OAuth 2.0 client-credentials access to an MCP server (docs/phase-2-execution-spec.md slice 2.3),
 * following the MCP authorization discovery chain:
 * <ol>
 *   <li>protected-resource metadata (RFC 9728), from the 401's {@code WWW-Authenticate
 *       resource_metadata} or the server's {@code /.well-known/oauth-protected-resource}; its
 *       {@code resource} must be the server being called;</li>
 *   <li>authorization-server metadata (RFC 8414, then OpenID discovery) for the token endpoint;</li>
 *   <li>a {@code client_credentials} token request bound to the server with {@code resource}
 *       (RFC 8707), client authenticated with HTTP Basic.</li>
 * </ol>
 * Every URL passes the egress guard and no redirect is followed. The token is cached until shortly
 * before it expires; the client secret is fetched from its supplier only when a token is needed.
 * Neither ever appears in an exception message.
 */
public final class McpOAuth implements McpAuth {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RESOURCE_METADATA = Pattern.compile("resource_metadata=\"([^\"]+)\"");
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);
    private static final int MAX_METADATA_BYTES = 64_000;

    private final URI server;
    private final String clientId;
    private final Supplier<String> clientSecret;
    private final Function<URI, EgressPolicy.Decision> guard;
    private final HttpClient http;
    private final Clock clock;
    private final Duration timeout;
    private String token;
    private Instant expiresAt = Instant.MIN;

    public McpOAuth(URI server, String clientId, Supplier<String> clientSecret, Function<URI, EgressPolicy.Decision> guard,
                    Duration timeout) {
        this(server, clientId, clientSecret, guard, timeout,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build(),
                Clock.systemUTC());
    }

    McpOAuth(URI server, String clientId, Supplier<String> clientSecret, Function<URI, EgressPolicy.Decision> guard,
             Duration timeout, HttpClient http, Clock clock) {
        this.server = server;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.guard = guard;
        this.timeout = timeout;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public synchronized String header() {
        return token != null && clock.instant().isBefore(expiresAt) ? "Bearer " + token : null;
    }

    @Override
    public synchronized boolean onUnauthorized(String wwwAuthenticate) {
        token = null;
        JsonNode resourceMetadata = resourceMetadata(wwwAuthenticate);
        String resource = resourceMetadata.path("resource").asText(null);
        if (resource == null || !sameResource(URI.create(resource), server)) {
            throw new McpException("the MCP server's protected-resource metadata names a different resource", false);
        }
        JsonNode servers = resourceMetadata.path("authorization_servers");
        if (!servers.isArray() || servers.isEmpty()) {
            throw new McpException("the MCP server's protected-resource metadata lists no authorization server", false);
        }
        JsonNode asMetadata = authorizationServerMetadata(URI.create(servers.get(0).asText()));
        String tokenEndpoint = asMetadata.path("token_endpoint").asText(null);
        if (tokenEndpoint == null) {
            throw new McpException("the authorization server publishes no token endpoint", false);
        }
        JsonNode grants = asMetadata.path("grant_types_supported");
        if (grants.isArray() && !grants.toString().contains("\"client_credentials\"")) {
            throw new McpException("the authorization server does not support the client_credentials grant", false);
        }
        requestToken(URI.create(tokenEndpoint), resource);
        return true;
    }

    private JsonNode resourceMetadata(String wwwAuthenticate) {
        Matcher m = RESOURCE_METADATA.matcher(wwwAuthenticate == null ? "" : wwwAuthenticate);
        List<URI> candidates = new ArrayList<>();
        if (m.find()) {
            candidates.add(URI.create(m.group(1)));
        } else {
            String path = server.getRawPath() == null || server.getRawPath().equals("/") ? "" : server.getRawPath();
            if (!path.isEmpty()) {
                candidates.add(origin(server).resolve("/.well-known/oauth-protected-resource" + path));
            }
            candidates.add(origin(server).resolve("/.well-known/oauth-protected-resource"));
        }
        for (URI candidate : candidates) {
            JsonNode doc = getJson(candidate, true);
            if (doc != null) {
                return doc;
            }
        }
        throw new McpException("the MCP server publishes no protected-resource metadata", false);
    }

    private JsonNode authorizationServerMetadata(URI issuer) {
        String path = issuer.getRawPath() == null || issuer.getRawPath().equals("/") ? "" : issuer.getRawPath();
        for (URI candidate : List.of(origin(issuer).resolve("/.well-known/oauth-authorization-server" + path),
                origin(issuer).resolve("/.well-known/openid-configuration" + path))) {
            JsonNode doc = getJson(candidate, true);
            if (doc != null) {
                return doc;
            }
        }
        throw new McpException("the authorization server publishes no metadata", false);
    }

    private void requestToken(URI tokenEndpoint, String resource) {
        check(tokenEndpoint);
        String secret = clientSecret.get();
        if (secret == null || secret.isBlank()) {
            throw new McpException("the connection's client secret could not be resolved", false);
        }
        String basic = Base64.getEncoder().encodeToString((form(clientId) + ":" + form(secret)).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint).timeout(timeout)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&resource=" + form(resource)))
                .build();
        JsonNode body = send(request, "token request");
        String accessToken = body == null ? null : body.path("access_token").asText(null);
        if (accessToken == null || !"bearer".equalsIgnoreCase(body.path("token_type").asText("bearer"))) {
            throw new McpException("the authorization server returned no bearer token", false);
        }
        long seconds = body.path("expires_in").asLong(300);
        token = accessToken;
        expiresAt = clock.instant().plusSeconds(seconds).minus(EXPIRY_MARGIN);
    }

    /** GET a metadata document; null on 404 so the next well-known location can be tried. */
    private JsonNode getJson(URI uri, boolean notFoundIsNull) {
        check(uri);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() == 404 && notFoundIsNull) {
                    return null;
                }
                if (response.statusCode() != 200) {
                    throw new McpException("metadata at " + uri + " answered HTTP " + response.statusCode(), false);
                }
                return parse(in.readNBytes(MAX_METADATA_BYTES + 1), "metadata at " + uri);
            }
        } catch (IOException e) {
            throw new McpException("metadata at " + uri + " could not be fetched (" + e.getClass().getSimpleName() + ")", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted", false);
        }
    }

    private JsonNode send(HttpRequest request, String what) {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new McpException(what + " was refused (HTTP " + response.statusCode() + ")", false);
                }
                return parse(in.readNBytes(MAX_METADATA_BYTES + 1), what);
            }
        } catch (IOException e) {
            throw new McpException(what + " failed (" + e.getClass().getSimpleName() + ")", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted", false);
        }
    }

    private static JsonNode parse(byte[] bytes, String what) {
        if (bytes.length > MAX_METADATA_BYTES) {
            throw new McpException(what + " is too large", false);
        }
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            throw new McpException(what + " is not valid JSON", false);
        }
    }

    private void check(URI uri) {
        EgressPolicy.Decision decision = guard.apply(uri);
        if (!decision.allowed()) {
            throw new McpException("OAuth destination not allowed: " + decision.reason(), false);
        }
    }

    /** The metadata's resource must be the server itself or its origin (a token for another resource is useless and unsafe). */
    static boolean sameResource(URI resource, URI server) {
        String r = resource.toString().replaceAll("/+$", "");
        String s = server.toString().replaceAll("/+$", "");
        return r.equals(s) || r.equals(origin(server).toString().replaceAll("/+$", ""));
    }

    private static URI origin(URI uri) {
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + "/");
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
