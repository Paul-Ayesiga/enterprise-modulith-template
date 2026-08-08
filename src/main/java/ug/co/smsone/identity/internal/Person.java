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
@Table(name = "person", schema = "platform")
@SQLDelete(sql = "update platform.person set deleted_at = now(), version = version + 1 where id = ? and version = ?")
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

    /**
     * Access stops. Two callers, and they mean different things — an administrator suspending a human
     * ({@code PersonAccessService.disable}) and the nightly sweep reacting to a Keycloak account that no
     * longer exists ({@code IdentityReconciliationJob}) — which is why each files its own {@code
     * audit_log} action rather than sharing one. The row itself records only that access stopped and
     * when; <em>why</em> is the trail's job, and a {@code disabled_reason} column would be a second,
     * staler spelling of it (it would still say "gone from Keycloak" long after somebody restored the
     * account there).
     *
     * <p>The status held BEFORE the disable is deliberately not recorded either. {@link #enable()}
     * reconstructs it from {@code activatedAt} — the same fact, one indirection away, and one that
     * cannot drift out of step with the lifecycle it describes.
     */
    void disable(Instant when) {
        this.status = ProvisioningStatus.DISABLED;
        this.disabledAt = when;
    }

    /**
     * Access resumes, at the point in the lifecycle it was taken away from: INVITED for someone who
     * never turned up, ACTIVE for someone who did.
     *
     * <p><b>{@code activatedAt} is what remembers which, and that is the whole reason no "status before
     * disable" column exists.</b> {@link #activate} stamps it at the single moment INVITED → ACTIVE
     * happens and nothing ever clears it, so a null there means the invitation was never taken up.
     * Restoring such a person to ACTIVE would mark an invitation as accepted by somebody who never
     * arrived: it destroys the "never signed in" signal invite expiry keys on, and it is the very write
     * {@code ProvisioningGateFilter} refuses to make on a target an operator is merely wearing. They go
     * back to INVITED instead, and the gate flips them on their own first real request — which is what
     * that flip has always meant.
     *
     * <p>Idempotent by arithmetic rather than by a guard, and that is worth noticing before "simplifying"
     * it into a plain assignment to ACTIVE: an ACTIVE person has an {@code activatedAt} and an INVITED
     * one has none, so re-running this on somebody who is not disabled computes the status they already
     * hold. The service still short-circuits first, because the decision that must not be re-made is
     * whether to write an {@code audit_log} row.
     *
     * <p>{@code disabledAt} is cleared because it answers "when did access stop" and access has not
     * stopped. The dates of the episode survive in {@code audit_log}, which is append-only and is where
     * a history belongs — a column left holding the last disable date on a live account is one the next
     * query written against it will read as "this account is disabled".
     */
    void enable() {
        this.status = activatedAt == null ? ProvisioningStatus.INVITED : ProvisioningStatus.ACTIVE;
        this.disabledAt = null;
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
