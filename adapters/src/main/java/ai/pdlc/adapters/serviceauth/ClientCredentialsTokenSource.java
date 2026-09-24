package ai.pdlc.adapters.serviceauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * OAuth 2.0 client-credentials grant (RFC 6749 §4.4) against the enterprise IdP's token endpoint,
 * with the access token cached until one minute before it expires. The client authenticates with
 * HTTP Basic ({@code client_secret_basic}). Deliberately tiny - no Spring - so any process in this
 * repo can use it; the build-worker has the equivalent in {@code build-worker/src/client.ts}.
 */
public final class ClientCredentialsTokenSource {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final URI tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final HttpClient http;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    private String token;
    private Instant refreshAt = Instant.MIN;

    public ClientCredentialsTokenSource(String tokenUri, String clientId, String clientSecret, String scope) {
        this(tokenUri, clientId, clientSecret, scope,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Clock.systemUTC());
    }

    ClientCredentialsTokenSource(String tokenUri, String clientId, String clientSecret, String scope, HttpClient http, Clock clock) {
        this.tokenUri = URI.create(tokenUri);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.http = http;
        this.clock = clock;
    }

    public synchronized String token() {
        if (token == null || !clock.instant().isBefore(refreshAt)) {
            fetch();
        }
        return token;
    }

    private void fetch() {
        String form = "grant_type=client_credentials"
                + (scope == null || scope.isBlank() ? "" : "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8));
        String basic = Base64.getEncoder().encodeToString(
                (encode(clientId) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Token endpoint " + tokenUri + " returned " + response.statusCode());
            }
            JsonNode body = mapper.readTree(response.body());
            JsonNode accessToken = body.get("access_token");
            if (accessToken == null || accessToken.asText().isBlank()) {
                throw new IllegalStateException("Token endpoint " + tokenUri + " returned no access_token");
            }
            long expiresIn = body.hasNonNull("expires_in") ? body.get("expires_in").asLong() : 300;
            Instant now = clock.instant();
            token = accessToken.asText();
            refreshAt = now.plusSeconds(expiresIn).minus(REFRESH_MARGIN);
            if (refreshAt.isBefore(now)) {
                refreshAt = now;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Token request to " + tokenUri + " failed", e);
        }
    }

    /** RFC 6749 §2.3.1: client id/secret are form-urlencoded before Basic encoding. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
