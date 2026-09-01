package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.identity.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiExceptionHandlerTest {

    @Test
    void missingUserHeaderThrowsUnauthenticated() {
        IdentityResolver resolver = new IdentityResolver();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User")).thenReturn(null);
        when(request.getHeader("X-Role")).thenReturn("PO");

        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void missingRoleHeaderThrowsUnauthenticated() {
        IdentityResolver resolver = new IdentityResolver();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User")).thenReturn("po@acme");
        when(request.getHeader("X-Role")).thenReturn("");

        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void bothHeadersPresentResolvesIdentity() {
        IdentityResolver resolver = new IdentityResolver();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User")).thenReturn("po@acme");
        when(request.getHeader("X-Role")).thenReturn("PO");

        var identity = resolver.resolve(request);
        assertThat(identity.user()).isEqualTo("po@acme");
        assertThat(identity.role()).isEqualTo("PO");
    }

    @Test
    void exceptionHandlerMapsUnauthenticatedTo401() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        var response = handler.handleUnauthenticated(new UnauthenticatedException("missing headers"));
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void exceptionHandlerMapsForbiddenTo403() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        var response = handler.handleForbidden(new ForbiddenException("not a checker"));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
