package ug.co.smsone.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.identity.PersonActivated;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * The canonical identity of a human on this platform: are they allowed in, where are they in that
 * lifecycle, and what are they called.
 *
 * <p>It holds no subject, no issuer and no e-mail. Those are, respectively, provider-shaped
 * ({@link ExternalIdentity}) and contact-shaped ({@link PersonContact}) — each with a different owner,
 * a different mutation rate, and for the address a verification state an identity has no business
 * carrying. That separation is the whole reason {@code app_user} was split rather than renamed:
 * adding a second identity provider is now an insert instead of a migration.
 */
@Entity
@Table(name = "person")
@SQLDelete(sql = "update person set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Person extends SoftDeletableEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProvisioningStatus status;

    @Column(name = "invited_at", nullable = false, updatable = false)
    private Instant invitedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    /**
     * When access stopped. {@code disable()} used to set the status and nothing else, so "when did this
     * account lose access" was answerable only by grepping {@code audit_log} — and retention and
     * erasure need a date, not a scan.
     */
    @Column(name = "disabled_at")
    private Instant disabledAt;

    // The SCIM/OIDC name. All seven are nullable and none of them is a display string — see PersonName.
    @Column(name = "formatted_name", length = 200)
    private String formattedName;

    @Column(name = "given_name", length = 100)
    private String givenName;

    @Column(name = "family_name", length = 100)
    private String familyName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(name = "honorific_prefix", length = 50)
    private String honorificPrefix;

    @Column(name = "honorific_suffix", length = 50)
    private String honorificSuffix;

    @Column(name = "preferred_name", length = 100)
    private String preferredName;

    protected Person() {
        // JPA
    }

    /**
     * A newly provisioned person. No {@code PersonProvisioned} event is registered here on purpose:
     * the id is assigned when the row is persisted, so an event built in this factory would carry
     * null. {@code PersonProvisioningService} publishes it explicitly after the save — the same
     * exception {@code DocumentService} makes.
     */
    static Person invited(PersonName name, Instant when) {
        Person person = new Person();
        person.status = ProvisioningStatus.INVITED;
        person.invitedAt = when;
        person.rename(name);
        return person;
    }

    /** First API hit after admin provisioning — flips INVITED → ACTIVE. Not JIT (the row pre-existed). */
    void activate(Instant when) {
        if (status == ProvisioningStatus.INVITED) {
            this.status = ProvisioningStatus.ACTIVE;
            this.activatedAt = when;
            registerEvent(new PersonActivated(getId(), when));
        }
    }

    void disable(Instant when) {
        this.status = ProvisioningStatus.DISABLED;
        this.disabledAt = when;
    }

    /**
     * Overwrites the whole name, because a name is one fact in seven columns: patching a part would let
     * a {@code givenName} from one provider sit beside a {@code formattedName} from another and render
     * as a person who does not exist.
     */
    void rename(PersonName name) {
        this.formattedName = name.formattedName();
        this.givenName = name.givenName();
        this.familyName = name.familyName();
        this.middleName = name.middleName();
        this.honorificPrefix = name.honorificPrefix();
        this.honorificSuffix = name.honorificSuffix();
        this.preferredName = name.preferredName();
    }

    ProvisioningStatus getStatus() {
        return status;
    }

    Instant getActivatedAt() {
        return activatedAt;
    }

    /** THE display value. Null is normal and means "we were never told" — never substitute the parts. */
    String getFormattedName() {
        return formattedName;
    }

    PersonName getName() {
        return new PersonName(formattedName, givenName, familyName, middleName, honorificPrefix,
                honorificSuffix, preferredName);
    }
}
