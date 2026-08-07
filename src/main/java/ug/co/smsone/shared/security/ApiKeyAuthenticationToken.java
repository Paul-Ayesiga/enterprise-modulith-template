package ug.co.smsone.shared.security;

import java.io.Serial;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authentication an accepted {@code X-Api-Key} yields. Platform keys carry their tier as a
 * ROLE authority (so {@code hasRole('platform-support')} routes just work); org keys carry NO
 * authorities — their permission subset is enforced by {@code ApiPermissionEvaluator}'s dedicated
 * branch, never by roles.
 *
 * <p>{@code final}, like {@link ImpersonatedAuthenticationToken}: the constructor calls
 * {@code setAuthenticated(true)}, so a subclass could observe a half-built {@code this} — and a subclass
 * of an authentication token is a way to claim authority the mint never granted. Nothing extends it.
 */
public final class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

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

    /**
     * The key's own id. Framework-facing only (logs, {@code authentication.name} expressions) — it names
     * a CREDENTIAL, not a who, and nothing may read it as an identity: the caller behind this token has
     * no person, which {@link CurrentUser#authenticationMethod()} states outright.
     */
    @Override
    public String getName() {
        return principal.keyId().toString();
    }
}
