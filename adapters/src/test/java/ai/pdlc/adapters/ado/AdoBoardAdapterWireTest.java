package ai.pdlc.adapters.ado;

import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** Validates the actual JSON-patch/response wire shapes AdoBoardAdapter sends and parses, without
 * needing a real ADO org (the env-gated {@link AdoBoardAdapterContractTest} covers the live path). */
class AdoBoardAdapterWireTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private static final Map<String, String> STATES = Map.of(
            "new", "New", "ready-for-story", "Ready for Story", "awaiting-G1", "Awaiting Approval");
    private static final Map<String, String> TYPES = Map.of("feature", "Feature");

    private AdoBoardAdapter adapter() {
        return new AdoBoardAdapter(wm.baseUrl(), "acme", "Payments", "fake-pat", STATES, TYPES,
                java.net.http.HttpClient.newHttpClient());
    }

    @Test
    void getItemMapsProviderStateToCanonicalState() {
        wm.stubFor(get(urlPathEqualTo("/acme/Payments/_apis/wit/workitems/4412"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"fields": {"System.Title": "Export CSV", "System.State": "Ready for Story",
                                "System.WorkItemType": "Feature", "System.Description": "desc", "System.AreaPath": "Payments"}}
                                """)));

        WorkItem item = adapter().getItem(new WorkItemRef("payments-squad", "4412"));

        assertThat(item.title()).isEqualTo("Export CSV");
        assertThat(item.state()).isEqualTo(CanonicalState.READY_FOR_STORY);
    }

    @Test
    void transitionSendsJsonPatchWithMappedProviderState() {
        wm.stubFor(patch(urlPathEqualTo("/acme/Payments/_apis/wit/workitems/4412"))
                .withRequestBody(equalToJson("[{\"op\":\"add\",\"path\":\"/fields/System.State\",\"value\":\"Ready for Story\"}]"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        adapter().transition(new WorkItemRef("payments-squad", "4412"), CanonicalState.READY_FOR_STORY);

        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor(urlPathEqualTo("/acme/Payments/_apis/wit/workitems/4412")));
    }

    @Test
    void createItemUsesConfiguredTypeAndAuthorizationHeader() {
        wm.stubFor(post(urlPathMatching("/acme/Payments/_apis/wit/workitems/\\$Feature"))
                .withHeader("Authorization", equalTo("Basic " + java.util.Base64.getEncoder()
                        .encodeToString(":fake-pat".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id": "4412", "fields": {"System.Title": "Export CSV", "System.State": "New",
                                "System.WorkItemType": "Feature", "System.Description": "", "System.AreaPath": ""}}
                                """)));

        WorkItem created = adapter().createItem("payments-squad", "feature", Map.of("title", "Export CSV"), null);

        assertThat(created.id()).isEqualTo("4412");
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathMatching("/acme/Payments/_apis/wit/workitems/\\$Feature")));
    }

    @Test
    void createItemWithIdempotencyKeyFindsExistingViaWiqlInsteadOfCreating() {
        wm.stubFor(post(urlPathEqualTo("/acme/Payments/_apis/wit/wiql"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"workItems\": [{\"id\": \"4412\"}]}")));
        wm.stubFor(get(urlPathEqualTo("/acme/Payments/_apis/wit/workitems/4412"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"fields": {"System.Title": "Export CSV", "System.State": "New",
                                "System.WorkItemType": "Feature", "System.Description": "", "System.AreaPath": ""}}
                                """)));

        WorkItem result = adapter().createItem("payments-squad", "feature",
                Map.of("title", "Export CSV", "_idempotencyKey", "feature-payments-squad-4412:v1:publishStory"), null);

        assertThat(result.id()).isEqualTo("4412");
        wm.verify(0, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathMatching("/acme/Payments/_apis/wit/workitems/\\$Feature")));
    }
}
