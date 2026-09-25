package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.UnauthenticatedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Exception → status mapping. Identity resolution itself is covered by {@code identity.IdentityResolverTest}. */
class ApiExceptionHandlerTest {

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
