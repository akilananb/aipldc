package ai.pdlc.controlplane.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The local-stack configuration ({@code PDLC_IDENTITY_DEV_HEADERS=true}, no tokens, no IdP): the
 * pre-slice-2 behaviour the e2e scripts and demo rely on is unchanged.
 */
@SpringBootTest(classes = SecurityTestApp.class, properties = "pdlc.identity.dev-headers=true")
@AutoConfigureMockMvc
class SecurityConfigDevHeadersTest {

    @Autowired
    MockMvc mvc;

    @Test
    void readsStayOpen() throws Exception {
        mvc.perform(get("/api/items")).andExpect(status().isOk());
    }

    @Test
    void mutationsUseTheHeadersWithoutCsrf() throws Exception {
        mvc.perform(post("/api/workspaces").header("X-User", "lead@acme").header("X-Role", "SquadLead"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("lead@acme"))
                .andExpect(jsonPath("$.role").value("SquadLead"));
    }

    @Test
    void mutationsWithoutHeadersAre401AsBefore() throws Exception {
        mvc.perform(post("/api/workspaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing X-User/X-Role headers"));
    }

    @Test
    void buildTasksBoardAndWebhooksStayOpenWithoutTokens() throws Exception {
        mvc.perform(post("/api/build-tasks/claim")).andExpect(status().isOk());
        mvc.perform(get("/api/board/local/1")).andExpect(status().isOk());
        mvc.perform(post("/webhooks/local")).andExpect(status().isOk());
    }

    @Test
    void meReportsDevHeadersMode() throws Exception {
        mvc.perform(get("/api/me").header("X-User", "po@acme").header("X-Role", "PO"))
                .andExpect(jsonPath("$.mode").value("dev-headers"))
                .andExpect(jsonPath("$.oidcEnabled").value(false))
                .andExpect(jsonPath("$.user").value("po@acme"));
    }
}
