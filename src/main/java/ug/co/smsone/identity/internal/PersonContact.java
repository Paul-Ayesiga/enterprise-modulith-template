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
import ug.co.smsone.shared.error.ConflictException;
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
 *
 * <p><b>An unverified row is INERT, and that is a rule the whole module leans on.</b> Since anyone may
 * now add an address to their own account ({@code MeContactController}), an unproven claim is a string a
 * stranger typed about somebody else's mailbox. So nothing resolves a person by one
 * ({@link PersonContactRepository#findVerifiedPersonIdsByValue} is what the directory port reads) and
 * nothing provisions against one (see {@link PersonContactRepository#findPersonIdsByValue}, which is
 * restricted to rows that are verified OR primary). The two states that a claim CAN reach —
 * {@link #verify} and {@link #makePrimary} — are the two doors, and both are locked behind a proof.
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
     * The human's own name for this address ("work", "billing"). Null for anything the platform wrote
     * itself — {@link #primaryEmail} has no label to give, because a provisioning invite is not somebody
     * naming their own mailbox.
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
     * {@code VERIFY_EMAIL} action and comes back with a token that says so — see
     * {@link PersonContacts#verifyWithProof}.
     *
     * <p><b>This is the one place a primary is set without a proof, and it has to be.</b> Assigning the
     * field directly rather than calling {@link #makePrimary} is deliberate: at invite time the address
     * is the only one that exists and nobody has had the chance to prove anything, so requiring a proof
     * here would make provisioning impossible. It is also exactly why "primary implies verified" cannot
     * be a CHECK constraint in V10 — the schema cannot tell this row apart from a chosen one. The rule
     * binds every path where a PERSON chooses, which is {@link #makePrimary}, and nothing else may
     * touch the field.
     */
    static PersonContact primaryEmail(UUID personId, String address) {
        PersonContact contact = new PersonContact();
        contact.personId = personId;
        contact.kind = ContactKind.EMAIL;
        contact.contactValue = address;
        contact.isPrimary = true;
        return contact;
    }

    /**
     * An address its owner has typed in but not yet proven: never primary, never trusted, and visible to
     * nothing except its own person's contact list until {@link #verify} runs.
     */
    static PersonContact claimed(UUID personId, ContactKind kind, String value, String label) {
        PersonContact contact = new PersonContact();
        contact.personId = personId;
        contact.kind = kind;
        contact.contactValue = value;
        contact.label = label;
        contact.isPrimary = false;
        return contact;
    }

    /** Idempotent: a second proof of the same address must not move the date the first one recorded. */
    void verify(Instant when) {
        if (verifiedAt == null) {
            this.verifiedAt = when;
        }
    }

    /**
     * Makes this the address of its kind the platform prefers.
     *
     * <p>The proof check lives HERE rather than in the service so that no future caller can set the flag
     * without one. It is not decoration: a primary address outranks every other in
     * {@link PersonContacts} and is one of the two states {@code findPersonIdsByValue} will provision
     * against, so a person who could promote an unproven claim could point either at a mailbox they do
     * not own.
     */
    void makePrimary() {
        if (verifiedAt == null) {
            throw new ConflictException(
                    "Verify this address before making it your primary one — an unproven address is "
                    + "not something this platform will send to or resolve you by.");
        }
        this.isPrimary = true;
    }

    /** Gives up the primary flag. Always safe: at-most-one is the constraint, at-least-one is not. */
    void standDown() {
        this.isPrimary = false;
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

    String getLabel() {
        return label;
    }

    boolean isPrimary() {
        return isPrimary;
    }

    boolean isVerified() {
        return verifiedAt != null;
    }

    Instant getVerifiedAt() {
        return verifiedAt;
    }
}
