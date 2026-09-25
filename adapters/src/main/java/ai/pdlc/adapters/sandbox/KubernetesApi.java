package ai.pdlc.adapters.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The few Kubernetes API calls the sandbox needs, over plain HTTPS with a service-account bearer
 * token and the cluster CA (in-cluster: {@code /var/run/secrets/kubernetes.io/serviceaccount}). No
 * client library: the sandbox's RBAC is create/get/list/delete on jobs, secrets and pods/log in one
 * namespace, and that is all this speaks.
 */
public final class KubernetesApi {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SERVICE_ACCOUNT = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");

    private final URI base;
    private final Supplier<String> token;
    private final HttpClient http;

    public KubernetesApi(URI base, Supplier<String> token, HttpClient http) {
        this.base = base;
        this.token = token;
        this.http = http;
    }

    /** The in-cluster API server, authenticated as the pod's service account. */
    public static KubernetesApi inCluster() throws IOException {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        return new KubernetesApi(URI.create("https://" + host + ":" + port), () -> {
            try {
                return Files.readString(SERVICE_ACCOUNT.resolve("token")).trim();
            } catch (IOException e) {
                throw new IllegalStateException("service account token unreadable", e);
            }
        }, clientTrusting(Files.readString(SERVICE_ACCOUNT.resolve("ca.crt"))));
    }

    /** An HTTP client that trusts exactly the given CA (PEM). */
    public static HttpClient clientTrusting(String caPem) {
        try {
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.US_ASCII)));
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("cluster-ca", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        } catch (Exception e) {
            throw new IllegalStateException("cluster CA could not be loaded", e);
        }
    }

    public JsonNode post(String path, JsonNode body) {
        return send(HttpRequest.newBuilder(base.resolve(path)).POST(HttpRequest.BodyPublishers.ofString(body.toString())), true);
    }

    /** Null on 404. */
    public JsonNode get(String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET(), false);
    }

    public String getText(String path) {
        HttpResponse<String> response = raw(HttpRequest.newBuilder(base.resolve(path)).GET());
        return response.statusCode() == 200 ? response.body() : "";
    }

    /** Foreground propagation: the Job's pods (and their processes) go before the Job is gone. Null on 404. */
    public JsonNode delete(String path) {
        String sep = path.contains("?") ? "&" : "?";
        return send(HttpRequest.newBuilder(base.resolve(path + sep + "propagationPolicy=Foreground"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody()), false);
    }

    public static String label(String selector) {
        return URLEncoder.encode(selector, StandardCharsets.UTF_8);
    }

    private JsonNode send(HttpRequest.Builder request, boolean mustSucceed) {
        HttpResponse<String> response = raw(request.header("Content-Type", "application/json"));
        if (response.statusCode() == 404 && !mustSucceed) {
            return null;
        }
        if (response.statusCode() >= 300) {
            String reason;
            try {
                reason = JSON.readTree(response.body()).path("message").asText("HTTP " + response.statusCode());
            } catch (IOException e) {
                reason = "HTTP " + response.statusCode();
            }
            throw new IllegalStateException("Kubernetes API refused the request: " + reason);
        }
        try {
            return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Kubernetes API returned invalid JSON", e);
        }
    }

    private HttpResponse<String> raw(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token.get())
                    .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Kubernetes API unreachable (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
