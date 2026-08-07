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
 * How the platform REACHES a person: an address, its label, whether it is the primary one of its kind,
 * and whether it was ever proven.
 *
 * <p>It lives in {@code identity}, not {@code profile}, because it is identity's own data rather than a
 * display preference — {@code app_user.email} lived here for that reason, and {@link
 * ug.co.smsone.identity.PersonDirectory} is what serves address lookups to the other modules. Parking
 * it in {@code profile} would invert that dependency: identity would have to call profile to answer a
 * question about its own accounts.
 *
 * <p>{@code verifiedAt} is load-bearing, not decoration. Nothing in the old schema recorded that an
 * address had been proven — the signup flow ran a real verification handshake and then threw the result
 * away — so the platform held an unverified string and treated it as authoritative. Uniqueness now
 * arrives one proof at a time: {@code uq_person_contact_verified_live} makes a verified address
 * globally unique, and a duplicate therefore fails AT VERIFICATION, where a human is present and an
 * error message makes sense, rather than at provisioning inside a batch job.
 */
@Entity
@Table(name = "person_contact")
@SQLDelete(sql = "update person_contact set deleted_at = now(), version = version + 1 "
        + "where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class PersonContact extends SoftDeletableEntity {

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private ContactKind kind;

    /** 320 = the RFC 5321 ceiling. The old column was 150, which truncates real long addresses. */
    @Column(name = "contact_value", nullable = false, length = 320)
    private String contactValue;

    /**
     * The human's own name for this address ("work", "billing"). Deliberately written by nothing today:
     * the only factory is {@link #primaryEmail}, which has no label to give, and there is no
     * multi-contact UI yet. Mapped so the column stays reachable the day that lands — the accessor is
     * not, because an accessor over an always-NULL column reads like data that exists.
     */
    @Column(length = 50)
    private String label;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    /** Null = claimed but never proven. */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    protected PersonContact() {
        // JPA
    }

    /**
     * The address a provisioning invite is sent to: primary for its person, and UNVERIFIED, because
     * nobody has proven anything yet. It becomes verified when the human completes the invite's
     * {@code VERIFY_EMAIL} action, not when an admin types it in.
     */
    static PersonContact primaryEmail(UUID personId, String address) {
        PersonContact contact = new PersonContact();
        contact.personId = personId;
        contact.kind = ContactKind.EMAIL;
        contact.contactValue = address;
        contact.isPrimary = true;
        return contact;
    }

    void verify(Instant when) {
        if (verifiedAt == null) {
            this.verifiedAt = when;
        }
    }

    UUID getPersonId() {
        return personId;
    }

    ContactKind getKind() {
        return kind;
    }

    String getContactValue() {
        return contactValue;
    }

    boolean isPrimary() {
        return isPrimary;
    }

    Instant getVerifiedAt() {
        return verifiedAt;
    }
}
