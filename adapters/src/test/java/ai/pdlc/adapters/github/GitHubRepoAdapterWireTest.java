package ai.pdlc.adapters.github;

import ai.pdlc.core.domain.CommitRef;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Base64;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class GitHubRepoAdapterWireTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private GitHubRepoAdapter adapter() {
        return new GitHubRepoAdapter(wm.baseUrl(), "https://github.com/acme/orders-service", "fake-token",
                java.net.http.HttpClient.newHttpClient());
    }

    @Test
    void readFileDecodesBase64Content() {
        String encoded = Base64.getEncoder().encodeToString("hello spec".getBytes());
        wm.stubFor(get(urlEqualTo("/repos/acme/orders-service/contents/openspec/specs/orders/spec.md?ref=main"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"content\": \"" + encoded + "\"}")));

        String content = adapter().readFile("main", "openspec/specs/orders/spec.md");

        assertThat(content).isEqualTo("hello spec");
    }

    @Test
    void writeFilesCreatesBlobsTreeCommitThenUpdatesRef() {
        wm.stubFor(get(urlPathEqualTo("/repos/acme/orders-service/git/ref/heads/story/4413"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"object\": {\"sha\": \"parent-sha\"}}")));
        wm.stubFor(get(urlPathEqualTo("/repos/acme/orders-service/git/commits/parent-sha"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"tree\": {\"sha\": \"base-tree-sha\"}}")));
        wm.stubFor(post(urlPathEqualTo("/repos/acme/orders-service/git/blobs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"sha\": \"blob-sha\"}")));
        wm.stubFor(post(urlPathEqualTo("/repos/acme/orders-service/git/trees"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"sha\": \"new-tree-sha\"}")));
        wm.stubFor(post(urlPathEqualTo("/repos/acme/orders-service/git/commits"))
                .withHeader("Authorization", equalTo("Bearer fake-token"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"sha\": \"new-commit-sha\"}")));
        wm.stubFor(patch(urlPathEqualTo("/repos/acme/orders-service/git/refs/heads/story/4413"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        CommitRef ref = adapter().writeFiles("story/4413",
                Map.of("openspec/changes/export-orders-csv/proposal.md", "# Proposal"),
                "story drafted", "po-agent");

        assertThat(ref.sha()).isEqualTo("new-commit-sha");
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor(
                urlPathEqualTo("/repos/acme/orders-service/git/refs/heads/story/4413")));
    }
}
