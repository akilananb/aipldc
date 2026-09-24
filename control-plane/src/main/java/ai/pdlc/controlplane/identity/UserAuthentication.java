package ai.pdlc.controlplane.identity;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/** A human user authenticated by the dev header shim; carries the resolved {@link Identity}. */
public final class UserAuthentication extends AbstractAuthenticationToken {

    private final Identity identity;

    public UserAuthentication(Identity identity) {
        super(AuthorityUtils.createAuthorityList(PdlcAuthorities.USER));
        this.identity = identity;
        setAuthenticated(true);
    }

    public Identity identity() {
        return identity;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return identity.user();
    }
}
