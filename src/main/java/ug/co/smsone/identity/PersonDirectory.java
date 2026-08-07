package ug.co.smsone.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public read port over {@code person}: address a human by {@code person.id}, the identifier this
 * platform mints and owns.
 *
 * <p>It replaces {@code UserDirectory}, whose whole job was handing out a Keycloak {@code sub} so
 * other modules could key on "the immutable id". That id was never ours: it was minted by one
 * provider, sized to that provider's UUID shape, and it could not name a person who signs in with a
 * second one. {@code person.id} is the same promise kept locally — immutable, single-valued per human,
 * and stable across every identity provider they ever link.
 *
 * <p><b>Nothing here speaks in issuers or subjects.</b> That translation happens once, inside this
 * module, at the seam that owns {@code external_identity}.
 */
public interface PersonDirectory {

    /**
     * The person reachable at this e-mail address, if any (case-insensitive).
     *
     * <p>A VERIFIED address wins over an unverified one holding the same value. That is not a
     * preference — it is the only ordering the database can guarantee is unambiguous: {@code
     * uq_person_contact_verified_live} makes a proven address globally unique, while unverified
     * duplicates are legal by design (a re-created account, a case variant). Preferring the oldest
     * claim instead would let a stale unverified row outrank the person who actually proved the
     * address.
     */
    Optional<UUID> findPersonIdByEmail(String email);

    /**
     * Reverse lookup, batched for windowed readers (the members export resolves one page of ids per
     * call): person id → primary e-mail for every id that has one. Ids with no e-mail on file are
     * simply absent from the map, exactly as unknown ids are — a caller that needs to tell the two
     * apart is asking a question this port deliberately does not answer.
     */
    Map<UUID, String> emailsByPersonIds(Collection<UUID> personIds);

    /**
     * The identity providers this person can sign in with — READ-ONLY: linking and unlinking are acts
     * performed at the provider (the Keycloak account console), and this platform displays them, it
     * never mutates them.
     *
     * <p>This used to be an HTTP call to Keycloak on every render. The links are rows now
     * ({@code external_identity}), so it is a local select — and it keeps answering for providers
     * Keycloak is not, which the remote call could never do.
     */
    List<LinkedAccount> linkedAccounts(UUID personId);

    /**
     * One external identity link: which provider, and what they are called over there.
     *
     * <p>{@code username} is display only. It is whatever the provider last told us, it is not unique,
     * and nothing may key on it.
     */
    record LinkedAccount(String provider, String username) {
    }
}
