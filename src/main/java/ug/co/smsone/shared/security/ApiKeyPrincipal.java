package ug.co.smsone.shared.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;
import java.util.UUID;

/**
 * An authenticated machine credential. Exactly one of the two authority shapes is present: an org key
 * carries {@code orgId} + a permission SUBSET (capped at mint by what its creator held); a platform key
 * carries {@code platformTier} (today always {@code platform-support} — machines read, humans change).
 *
 * <p>There is no {@code subject()} here any more. It returned {@code "key:" + keyId}: a non-person
 * principal squeezed into a person-shaped hole, and the hole was load-bearing — that string was written
 * into {@code created_by}, which is why V10 could say the column "already did NOT hold a Keycloak sub"
 * for every row a machine touched. A machine now says what it is: an organization, a permission set, and
 * {@link AuthenticationMethod#API_KEY}. {@link #keyId()} is the credential's identity and is the only
 * thing that may stand in for one; {@code created_by} takes NULL, because no person did this.
 *
 * <p>{@code Serializable} because it is the principal of an {@code AbstractAuthenticationToken}, which is
 * itself serializable: a session store that writes the {@code SecurityContext} out has to be able to
 * write this too, and a non-serializable principal inside a serializable token is a runtime failure that
 * only shows up under the store, never in a test that builds the token in memory. Every component here
 * is already serializable ({@code UUID}, {@code String}, and the immutable {@code Set} the mint hands in).
 */
public record ApiKeyPrincipal(UUID keyId, String name, UUID orgId, Set<String> permissions,
        String platformTier) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
