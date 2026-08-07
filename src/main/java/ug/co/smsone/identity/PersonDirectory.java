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
     * The person who has PROVEN this e-mail address, if any (case-insensitive).
     *
     * <p><b>Verified addresses only, and that is the contract — not an implementation detail.</b> An
     * unverified {@code person_contact} row is a string somebody typed, and since a person may add
     * addresses to their own account it may well name a mailbox they have never had access to. This
     * method answers "whose address is this", so honouring an unproven row would make adding one an
     * account-takeover primitive: park a claim on a colleague's address and every caller that resolves a
     * person by address hands you their place. A caller that gets {@link Optional#empty()} for an
     * address it can see on screen is being told the truth — nobody has established it.
     *
     * <p>The database backs the answer rather than the code merely preferring it:
     * {@code uq_person_contact_verified_live} makes a proven address globally unique, so there is at
     * most one person to return. Unverified duplicates stay legal by design (a re-created account, a
     * case variant) precisely because nothing resolves through them.
     */
    Optional<UUID> findPersonIdByEmail(String email);

    /**
     * Reverse lookup, batched for windowed readers (the members export resolves one page of ids per
     * call): person id → primary e-mail for every id that has one. Ids with no e-mail on file are
     * simply absent from the map, exactly as unknown ids are — a caller that needs to tell the two
     * apart is asking a question this port deliberately does not answer.
     *
     * <p><b>This is "where do we send", not "who is this", and the difference is the opposite of
     * pedantry.</b> It answers WITH an unverified address when that is all a person has, because the
     * invite this platform mails to a brand-new account has to go somewhere and nobody has proven
     * anything yet. It is safe in that direction and only that direction: the caller already knows which
     * person it is asking about. Never invert it — matching an address back to a person is
     * {@link #findPersonIdByEmail}, which refuses to answer from an unproven row for exactly this
     * reason.
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
