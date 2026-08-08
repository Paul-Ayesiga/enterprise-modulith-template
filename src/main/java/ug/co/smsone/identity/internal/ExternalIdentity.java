package ug.co.smsone.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * One link between a {@link Person} and an account they hold somewhere else: which provider, at which
 * issuer, under which subject over there.
 *
 * <p>This is the <b>only</b> place in the system permitted to store an identifier minted elsewhere, and
 * the only place an issuer or a subject exists at all. Everything above {@link PersonResolver} speaks
 * {@code person.id}.
 *
 * <p>{@code issuer} is never null and never blank. Postgres treats NULLs as distinct in a unique index,
 * so a nullable issuer would let {@code (KEYCLOAK, null, 'abc')} be inserted twice — two people
 * claiming one external identity, exactly the duplicate {@code uq_external_identity_subject_live}
 * exists to forbid. Providers with no issuer URL write a canonical constant, never null.
 */
@Entity
@Table(name = "external_identity", schema = "platform")
@SQLDelete(sql = "update platform.external_identity set deleted_at = now(), version = version + 1 "
        + "where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class ExternalIdentity extends SoftDeletableEntity {

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private IdentityProvider provider;

    /** The realm/tenant this subject is unique WITHIN. 'GOOGLE' names a product, not a namespace. */
    @Column(nullable = false, updatable = false, length = 300)
    private String issuer;

    @Column(name = "external_subject", nullable = false, updatable = false, length = 255)
    private String externalSubject;

    /** What they are called over there. DISPLAY ONLY — not unique, not stable, never a key. */
    @Column(name = "external_username", length = 320)
    private String externalUsername;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    /**
     * Mapped, and deliberately written by nothing today. Stamping it per request would turn every
     * authenticated read into a write on the hottest index in the schema; it is here for a sign-in
     * webhook or a reconciliation pass to fill in, and an honest null beats a value the code pretends
     * to maintain. Stated rather than discovered, the same way the schema states API_KEY is reserved.
     */
    @Column(name = "last_authenticated_at")
    private Instant lastAuthenticatedAt;

    protected ExternalIdentity() {
        // JPA
    }

    static ExternalIdentity link(UUID personId, IdentityProvider provider, String issuer,
            String externalSubject, String externalUsername, Instant when) {
        ExternalIdentity identity = new ExternalIdentity();
        identity.personId = personId;
        identity.provider = provider;
        identity.issuer = issuer;
        identity.externalSubject = externalSubject;
        identity.externalUsername = externalUsername;
        identity.linkedAt = when;
        return identity;
    }

    UUID getPersonId() {
        return personId;
    }

    IdentityProvider getProvider() {
        return provider;
    }

    String getIssuer() {
        return issuer;
    }

    String getExternalSubject() {
        return externalSubject;
    }

    String getExternalUsername() {
        return externalUsername;
    }
}
