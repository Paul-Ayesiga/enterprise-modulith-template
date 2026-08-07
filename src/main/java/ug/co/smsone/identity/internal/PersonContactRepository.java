package ug.co.smsone.identity.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PersonContactRepository extends JpaRepository<PersonContact, UUID> {

    /**
     * <b>The directory resolve: who is PROVEN to be reachable at this address.</b> Verified rows only,
     * and that predicate is the security boundary of this whole table.
     *
     * <p>It is a separate query from {@link #findPersonIdsByValue} because the two questions stopped
     * having the same answer the moment a person could add an address to their own account. This one
     * answers "whose address is this", and an unverified row is not evidence of that: it is a string
     * somebody typed, which may name a mailbox they have never had access to. If an unproven claim could
     * satisfy it, adding one would be an account-takeover primitive — park a claim on
     * {@code victim@corp.com} and every caller that resolves a person by address hands you their place.
     *
     * <p>No ordering, and none is needed: {@code uq_person_contact_verified_live} makes (kind, folded
     * value) unique across LIVE VERIFIED rows, so this returns at most one id by construction. The
     * {@code Limit} is the caller's, not a tie-break.
     */
    @Query("""
            select c.personId from PersonContact c
            where c.kind = :kind and lower(c.contactValue) = lower(:value) and c.verifiedAt is not null
            """)
    List<UUID> findVerifiedPersonIdsByValue(@Param("kind") ContactKind kind, @Param("value") String value,
            Limit limit);

    /**
     * <b>The provisioning find-or-create probe, and nothing else.</b> Its one caller is
     * {@code PersonProvisioningService.findOrCreatePerson}, asking "is there already an account this
     * invite belongs to, or do I mint one?" — a different question from the resolve above, which is why
     * it tolerates a row nobody has proven.
     *
     * <p><b>Verified OR primary, never a bare claim.</b> A primary row is one the platform itself wrote
     * when it sent an invite to that address ({@link PersonContact#primaryEmail}), or one its owner
     * promoted after proving it ({@link PersonContact#makePrimary}); either way it is the address an
     * account was established at, so a re-invite has to find it or every retry would mint a second
     * person. A self-added, unproven, non-primary claim is neither, and it must be invisible here: an
     * attacker who could park {@code victim@corp.com} on their own account before anyone invited it
     * would have this method hand them the invite. Silently — {@code provision()} sees their existing
     * Keycloak link, reports {@code alreadyExisted}, sends no mail, and the caller adds the ATTACKER to
     * the organization while the victim never hears about it.
     *
     * <p>The ordering still puts a VERIFIED row first, because both states can exist for one address on
     * DIFFERENT people: person A proved it, person B was invited to it before A's proof landed. Proof
     * wins over a claim the platform merely recorded. {@code createdAt, id} breaks the remaining tie so
     * two calls never disagree.
     */
    @Query("""
            select c.personId from PersonContact c
            where c.kind = :kind and lower(c.contactValue) = lower(:value)
              and (c.verifiedAt is not null or c.isPrimary = true)
            order by case when c.verifiedAt is null then 1 else 0 end, c.createdAt asc, c.id asc
            """)
    List<UUID> findPersonIdsByValue(@Param("kind") ContactKind kind, @Param("value") String value, Limit limit);

    /**
     * Every contact of one kind for a page of people, in ONE query. The caller reduces to one address
     * per person: batching here and choosing there keeps the "which address wins" rule in a single
     * place instead of duplicating it into SQL that cannot express it without a window function.
     */
    @Query("select c from PersonContact c where c.personId in :personIds and c.kind = :kind")
    List<PersonContact> findByPersonIds(@Param("personIds") Collection<UUID> personIds,
            @Param("kind") ContactKind kind);

    /**
     * One person's whole contact book, best-first within each kind — the {@code /me/contacts} read, and
     * the input to every write rule in {@link PersonContacts}.
     *
     * <p>Unpaginated on purpose, and safe because it is BOUNDED: {@code PersonContacts.MAX_PER_KIND}
     * caps a person at ten addresses per kind, so this select can never walk more rows than a contact
     * book has. ADR 0002 forbids OFFSET and totals over open-ended collections; a keyset cursor over a
     * set with a hard ceiling would be machinery with no reader.
     *
     * <p>The order repeats {@code PersonContacts.BEST_FIRST} in HQL rather than sorting in Java so the
     * list a person reads is the same ranking every other caller acts on. {@code isPrimary desc} puts
     * true first (Postgres sorts false below true); the {@code case} makes "proven" the second key
     * without needing a window function.
     */
    @Query("""
            select c from PersonContact c where c.personId = :personId
            order by c.kind asc, c.isPrimary desc,
                     case when c.verifiedAt is null then 1 else 0 end, c.createdAt asc, c.id asc
            """)
    List<PersonContact> findAllOf(@Param("personId") UUID personId);

    /**
     * A contact BY ID AND OWNER. Never {@code findById} followed by an owner check: the two-step reads
     * the same but leaks — a wrong owner has already loaded the row, and every later edit to that
     * handler is one {@code return} away from acting on it. Somebody else's contact and a contact that
     * does not exist are the same 404 here, which is also the only honest answer to give.
     */
    Optional<PersonContact> findByIdAndPersonId(UUID id, UUID personId);
}
