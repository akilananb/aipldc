package ai.pdlc.controlplane.identity;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production-shaped configuration: dev headers off, OIDC login configured (explicit provider
 * endpoints, no discovery), service JWTs verified against a test RSA key, shared build-agent and
 * webhook tokens set.
 */
@SpringBootTest(classes = SecurityTestApp.class, properties = {
        "pdlc.identity.dev-headers=false",
        "pdlc.identity.webhook-token=hook-secret",
        "pdlc.identity.role-mapping.pdlc-admins=Admin",
        "pdlc.identity.role-mapping.pdlc-po=PO",
        "pdlc.build-agent.token=agent-secret",
        "spring.security.oauth2.client.registration.pdlc.client-id=pdlc-ui",
        "spring.security.oauth2.client.registration.pdlc.client-secret=s3cret",
        "spring.security.oauth2.client.registration.pdlc.scope=openid,email",
        "spring.security.oauth2.client.registration.pdlc.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.pdlc.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.provider.pdlc.authorization-uri=https://idp.example/authorize",
        "spring.security.oauth2.client.provider.pdlc.token-uri=https://idp.example/token",
        "spring.security.oauth2.client.provider.pdlc.jwk-set-uri=https://idp.example/jwks",
        "spring.security.oauth2.client.provider.pdlc.user-name-attribute=sub"})
@AutoConfigureMockMvc
@Import(SecurityConfigOidcTest.Keys.class)
class SecurityConfigOidcTest {

    static final RSAKey KEY = generate();

    @TestConfiguration
    static class Keys {
        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            return NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousApiCallsGetJson401NotALoginRedirect() throws Exception {
        mvc.perform(get("/api/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void anAnonymousMutationIs401EvenThoughCsrfFailsFirst() throws Exception {
        mvc.perform(post("/api/workspaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void devHeadersAreIgnoredWhenTheFlagIsOff() throws Exception {
        mvc.perform(get("/api/items").header("X-User", "po@acme").header("X-Role", "Admin"))
                .andExpect(status().isUnauthorized());
    }

    /** Fresh context: spring-security-test's {@code csrf()} swaps the filter's token repository for
     * the rest of a context's life, which would hide the real cookie-writing repository. */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void meReportsOidcModeAndIssuesTheCsrfCookie() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("oidc"))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.loginUrl").value("/oauth2/authorization/pdlc"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void loginStartsTheAuthorizationCodeFlowAtTheIdp() throws Exception {
        mvc.perform(get("/oauth2/authorization/pdlc"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("https://idp.example/authorize")));
    }

    @Test
    void aMappedOidcUserReadsAndMutatesWithCsrf() throws Exception {
        var admin = oidcLogin()
                .idToken(t -> t.subject("u-1").claim("email", "ada@acme").claim("groups", List.of("pdlc-po", "pdlc-admins")))
                .authorities(new SimpleGrantedAuthority(PdlcAuthorities.USER));

        mvc.perform(get("/api/items").with(admin)).andExpect(status().isOk());
        mvc.perform(post("/api/workspaces").with(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Invalid or missing CSRF token"));
        mvc.perform(post("/api/workspaces").with(admin).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("ada@acme"))
                .andExpect(jsonPath("$.role").value("Admin"));
        mvc.perform(get("/api/me").with(admin))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.role").value("Admin"));
    }

    @Test
    void anOidcUserWithNoMappedGroupIsAuthenticatedButForbidden() throws Exception {
        var stranger = oidcLogin().idToken(t -> t.subject("u-2").claim("email", "eve@acme").claim("groups", List.of("sales")));

        mvc.perform(get("/api/items").with(stranger)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me").with(stranger))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.error").value("No PDLC role is mapped for eve@acme"));
    }

    @Test
    void aBuildServiceJwtReachesOnlyBuildTasks() throws Exception {
        String token = jwt("build-worker", "pdlc.build");

        mvc.perform(post("/api/build-tasks/claim").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/items").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/workspaces").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/board/local/1").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void aBoardServiceJwtReadsTheBoardOnly() throws Exception {
        String token = jwt("agents", "pdlc.board.read");

        mvc.perform(get("/api/board/local/1").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/build-tasks/claim").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void aForgedJwtIsRejected() throws Exception {
        RSAKey other = generate();
        String forged = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(other))).encode(JwtEncoderParameters.from(
                JwtClaimsSet.builder().subject("x").claim("scope", "pdlc.build").expiresAt(Instant.now().plusSeconds(60)).build()))
                .getTokenValue();

        mvc.perform(post("/api/build-tasks/claim").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void theSharedAgentTokenStillWorksAndAWrongOneDoesNot() throws Exception {
        mvc.perform(post("/api/build-tasks/claim").header("X-Agent-Token", "agent-secret")).andExpect(status().isOk());
        mvc.perform(post("/api/build-tasks/claim").header("X-Agent-Token", "guess")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/build-tasks/claim")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/items").header("X-Agent-Token", "agent-secret")).andExpect(status().isForbidden());
    }

    @Test
    void webhooksNeedTheWebhookToken() throws Exception {
        mvc.perform(post("/webhooks/local")).andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/local").header("X-Webhook-Token", "hook-secret")).andExpect(status().isOk());
    }

    @Test
    void corsAllowsCredentialedCallsFromTheUiOrigin() throws Exception {
        mvc.perform(options("/api/items")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/api/items")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutEndsTheSessionWith204() throws Exception {
        var admin = oidcLogin().idToken(t -> t.claim("groups", List.of("pdlc-admins")))
                .authorities(new SimpleGrantedAuthority(PdlcAuthorities.USER));
        mvc.perform(post("/logout").with(admin).with(csrf())).andExpect(status().isNoContent());
    }

    private static String jwt(String subject, String scope) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY))).encode(JwtEncoderParameters.from(
                JwtClaimsSet.builder().subject(subject).claim("scope", scope).expiresAt(Instant.now().plusSeconds(300)).build()))
                .getTokenValue();
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
