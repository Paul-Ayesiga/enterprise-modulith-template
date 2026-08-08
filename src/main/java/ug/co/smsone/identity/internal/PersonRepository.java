package ug.co.smsone.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ug.co.smsone.identity.ProvisioningStatus;

interface PersonRepository extends JpaRepository<Person, UUID>, JpaSpecificationExecutor<Person> {

    /**
     * The gate's question, and only it: may this person in. One column, so the access check on every
     * authenticated {@code /api/**} request stops hydrating the whole aggregate — and stops leaving a
     * managed instance in the persistence context that nothing ever writes back.
     *
     * <p>This read is deliberately NOT cached, unlike the {@code (issuer, sub) → person.id} translation
     * that precedes it: this one IS the access decision, and disabling or erasing an account has to bite
     * on the very next request. {@code @SQLRestriction} applies (HQL), so an erased person is an empty
     * Optional here — which {@code PersonAccessService} reads as DISABLED.
     */
    @Query("select p.status from Person p where p.id = :id")
    Optional<ProvisioningStatus> statusById(@Param("id") UUID id);

    /**
     * Native, because {@code @SQLRestriction} hides the very rows this asks about: to every HQL query a
     * deleted person and a person who never existed are the same absence, and they must not lead to the
     * same access decision — one is a hard stop, the other is an invitation to onboard.
     */
    @Query(value = "select exists(select 1 from platform.person where id = :id and deleted_at is not null)",
            nativeQuery = true)
    boolean existsDeletedById(@Param("id") UUID id);

    /**
     * Serialises find-or-create for one e-mail address for the rest of the caller's transaction.
     *
     * <p>Without it, two concurrent invites of the same new address both read no person under READ
     * COMMITTED and both insert one — and nothing in the schema stops them, because uniqueness on
     * {@code person_contact} covers VERIFIED addresses only (deliberately: a re-created account and a
     * case variant must be able to coexist unproven). The result would be two people who are one human,
     * discovered later by whichever of them verified first.
     *
     * <p>Advisory rather than {@code select … for update} for the reason that always applies to
     * read-then-insert: the contended case is the one where there is no row yet, so there is nothing to
     * lock. Hash collisions between unrelated addresses only over-serialise, which is harmless at
     * invite rates, and the lock is released with the transaction so no path can leak it. The same
     * pattern, for the same reason, as {@link ImpersonationSessionRepository#lockPair}.
     */
    @Query(value = "select count(*) from (select pg_advisory_xact_lock(cast(hashtext(:key) as bigint))) taken",
            nativeQuery = true)
    long lockEmail(@Param("key") String key);
}
