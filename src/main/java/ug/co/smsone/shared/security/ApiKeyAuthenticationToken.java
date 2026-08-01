package ug.co.smsone.shared.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authentication an accepted {@code X-Api-Key} yields. Platform keys carry their tier as a
 * ROLE authority (so {@code hasRole('platform-support')} routes just work); org keys carry NO
 * authorities — their permission subset is enforced by {@code ApiPermissionEvaluator}'s dedicated
 * branch, never by roles.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final ApiKeyPrincipal principal;

    public ApiKeyAuthenticationToken(ApiKeyPrincipal principal) {
        super(principal.platformTier() == null
                ? List.<GrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + principal.platformTier())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public ApiKeyPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return "N/A";
    }

    @Override
    public String getName() {
        return principal.subject();
    }
}
