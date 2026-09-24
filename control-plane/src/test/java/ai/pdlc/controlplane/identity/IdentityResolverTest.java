package ai.pdlc.controlplane.identity;

import ai.pdlc.controlplane.web.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityResolverTest {

    private final IdentityProperties props = new IdentityProperties(false, null, null,
            Map.of("pdlc-admins", "Admin", "pdlc-po", "PO", "pdlc-qa", "QA"), null, null, null, null);
    private final OidcIdentityMapper mapper = new OidcIdentityMapper(props);
    private final IdentityResolver resolver = new IdentityResolver(props, mapper);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nothingAuthenticatedIs401() {
        assertThatThrownBy(resolver::current)
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("Authentication required");
    }

    @Test
    void devHeaderUserResolvesAsSent() {
        SecurityContextHolder.getContext().setAuthentication(new UserAuthentication(new Identity("po@acme", "PO")));

        assertThat(resolver.current()).isEqualTo(new Identity("po@acme", "PO"));
    }

    @Test
    void oidcUserGetsTheHighestMappedRoleAndTheConfiguredUserClaim() {
        login(Map.of("sub", "u-1", "email", "ada@acme", "groups", List.of("pdlc-qa", "pdlc-po", "unrelated")));

        assertThat(resolver.current()).isEqualTo(new Identity("ada@acme", "PO"));
    }

    @Test
    void oidcUserWithoutTheUserClaimFallsBackToSubject() {
        login(Map.of("sub", "u-9", "groups", "pdlc-admins"));

        assertThat(resolver.current()).isEqualTo(new Identity("u-9", "Admin"));
    }

    @Test
    void oidcUserWithNoMappedGroupIsForbidden() {
        login(Map.of("sub", "u-2", "email", "eve@acme", "groups", List.of("sales")));

        assertThatThrownBy(resolver::current).isInstanceOf(ForbiddenException.class).hasMessageContaining("eve@acme");
    }

    @Test
    void serviceCredentialsCannotActAsAUser() {
        SecurityContextHolder.getContext().setAuthentication(new ServiceAuthentication("build-worker", PdlcAuthorities.SCOPE_BUILD));

        assertThatThrownBy(resolver::current).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void authoritiesMapperGrantsUserOnlyToMappedLogins() {
        var mapped = new OidcUserAuthority(idToken(Map.of("sub", "a", "groups", List.of("pdlc-qa"))));
        var unmapped = new OidcUserAuthority(idToken(Map.of("sub", "b", "groups", List.of("sales"))));

        assertThat(mapper.mapAuthorities(List.of(mapped))).extracting(Object::toString).contains(PdlcAuthorities.USER);
        assertThat(mapper.mapAuthorities(List.of(unmapped))).extracting(Object::toString).doesNotContain(PdlcAuthorities.USER);
    }

    @Test
    void headerTokensCompareExactly() {
        assertThat(HeaderAuthenticationFilter.matches("secret", "secret")).isTrue();
        assertThat(HeaderAuthenticationFilter.matches("secret", "secreT")).isFalse();
        assertThat(HeaderAuthenticationFilter.matches("", "")).isFalse();
        assertThat(HeaderAuthenticationFilter.matches("secret", null)).isFalse();
    }

    private void login(Map<String, Object> claims) {
        OidcIdToken token = idToken(claims);
        DefaultOidcUser user = new DefaultOidcUser(List.of(new OidcUserAuthority(token)), token, "sub");
        SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "pdlc"));
    }

    private static OidcIdToken idToken(Map<String, Object> claims) {
        return new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(60), claims);
    }
}
