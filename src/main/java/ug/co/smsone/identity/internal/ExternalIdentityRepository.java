package ug.co.smsone.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {

    /**
     * THE resolution: the one lookup every authenticated request performs, turning a validated
     * (provider, issuer, subject) triple into a person. Backed by
     * {@code uq_external_identity_subject_live}, which is the index that must never be dropped.
     *
     * <p>A scalar projection, not the entity: the edge wants one uuid, and hydrating the aggregate to
     * read one column put a managed instance in the persistence context on every authenticated request
     * for nothing. {@code @SQLRestriction} still applies — this is HQL — so a soft-deleted link is
     * invisible here exactly as it is to the derived finders, which is what makes the answer match the
     * PARTIAL unique index backing it.
     */
    @Query("select i.personId from ExternalIdentity i "
            + "where i.provider = :provider and i.issuer = :issuer and i.externalSubject = :externalSubject")
    Optional<UUID> personIdByLink(@Param("provider") IdentityProvider provider,
            @Param("issuer") String issuer, @Param("externalSubject") String externalSubject);

    /** The entity form of the same lookup — provisioning needs the row, not just the id. */
    Optional<ExternalIdentity> findByProviderAndIssuerAndExternalSubject(
            IdentityProvider provider, String issuer, String externalSubject);

    /** Is this person already linked at this issuer? The idempotency check provisioning turns on. */
    Optional<ExternalIdentity> findByPersonIdAndProviderAndIssuer(
            UUID personId, IdentityProvider provider, String issuer);

    /** The reverse read: every link this person holds — the linked-accounts surface. */
    List<ExternalIdentity> findByPersonIdOrderByLinkedAtAsc(UUID personId);

    /**
     * Native for the same reason as {@link PersonRepository#existsDeletedById}: an unlinked subject and
     * an unlinked-because-erased subject are the same absence to HQL and must decide differently.
     */
    @Query(value = """
            select exists(select 1 from external_identity
                          where provider = :provider and issuer = :issuer
                            and external_subject = :externalSubject and deleted_at is not null)
            """, nativeQuery = true)
    boolean existsDeletedBySubject(@Param("provider") String provider, @Param("issuer") String issuer,
            @Param("externalSubject") String externalSubject);
}
