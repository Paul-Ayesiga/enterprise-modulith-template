package ug.co.smsone.shared.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The {@code Authentication} a request carries while an impersonation session is active. The principal
 * is the {@link ImpersonatedPrincipal}; {@link #getName()} is the target's subject, so everything that
 * keys on the authenticated name sees the effective identity.
 *
 * <p>The authority collection is EMPTY, deliberately and permanently — that emptiness <em>is</em> the
 * mechanism. Org permissions still resolve from the database for the target exactly as they would for
 * the real user, so tenant endpoints work; every {@code hasRole('platform-*')} check fails, so
 * {@code /admin/**} stays out of reach and an operator cannot launder their own tier through a session
 * opened on themselves. Granting an authority here would quietly undo both halves at once.
 *
 * <p>Package-private, unlike {@link ImpersonationLookup} and {@link ImpersonatedPrincipal}, which are
 * public because {@code identity} implements and returns them. Nothing outside this package needs to
 * name the token, and {@code shared} is the OPEN module: a public constructor here would let any
 * business module mint an impersonated principal with no session row, no reason, no deadline and no
 * audit trail — the one construction path this class's guarantees assume does not exist.
 */
final class ImpersonatedAuthenticationToken extends AbstractAuthenticationToken {

    private final ImpersonatedPrincipal principal;

    ImpersonatedAuthenticationToken(ImpersonatedPrincipal principal) {
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public ImpersonatedPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null; // the actor's own bearer token authenticated the request; a session id is not a credential
    }

    /** The EFFECTIVE identity — the target's subject. The actor stays reachable through the principal. */
    @Override
    public String getName() {
        return principal.targetSubject();
    }
}
