package ai.pdlc.controlplane.identity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps an enterprise IdP's OIDC claims onto a PDLC {@link Identity}: the user id comes from
 * {@code pdlc.identity.user-claim} (falling back to {@code sub}); the role is the highest PDLC role
 * any of the user's {@code pdlc.identity.role-claim} groups maps to, by the precedence in
 * {@link #ROLE_PRECEDENCE}. A user none of whose groups map is authenticated but gets no
 * {@link PdlcAuthorities#USER} authority, so every user endpoint answers 403.
 */
@Component
public class OidcIdentityMapper implements GrantedAuthoritiesMapper {

    static final List<String> ROLE_PRECEDENCE = List.of("Admin", "SquadLead", "PO", "QA", "FSDeveloper");

    private final IdentityProperties properties;

    public OidcIdentityMapper(IdentityProperties properties) {
        this.properties = properties;
    }

    /** The identity for these claims, or empty when no group maps to a PDLC role. */
    public Optional<Identity> identity(Map<String, Object> claims) {
        return role(claims).map(role -> new Identity(user(claims), role));
    }

    public String user(Map<String, Object> claims) {
        Object user = claims.get(properties.userClaim());
        if (user instanceof String s && !s.isBlank()) {
            return s;
        }
        return String.valueOf(claims.get("sub"));
    }

    public Optional<String> role(Map<String, Object> claims) {
        Set<String> mapped = new HashSet<>();
        for (String group : groups(claims.get(properties.roleClaim()))) {
            String role = properties.roleMapping().get(group);
            if (role != null) {
                mapped.add(role);
            }
        }
        return ROLE_PRECEDENCE.stream().filter(mapped::contains).findFirst();
    }

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> result = new HashSet<>(authorities);
        for (GrantedAuthority authority : authorities) {
            if (authority instanceof OidcUserAuthority oidc) {
                Map<String, Object> claims = oidc.getUserInfo() != null
                        ? merge(oidc.getIdToken().getClaims(), oidc.getUserInfo().getClaims())
                        : oidc.getIdToken().getClaims();
                if (role(claims).isPresent()) {
                    result.add(new SimpleGrantedAuthority(PdlcAuthorities.USER));
                }
            }
        }
        return result;
    }

    private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> merged = new java.util.HashMap<>(a);
        merged.putAll(b);
        return merged;
    }

    private static List<String> groups(Object claim) {
        if (claim instanceof String s) {
            return List.of(s.split("[\\s,]+"));
        }
        if (claim instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
