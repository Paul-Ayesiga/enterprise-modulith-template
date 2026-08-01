package ug.co.smsone.subscription.internal;

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

/** One org's commercial state: which plan, in what standing. No row at all means FREE. */
@Entity
@Table(name = "org_subscription")
@SQLDelete(sql = "update org_subscription set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class OrgSubscription extends SoftDeletableEntity {

    enum Status { ACTIVE, TRIALING, PAST_DUE, CANCELLED, PAUSED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    /** When a TRIALING trial lapses; the expiry job pauses the subscription past this instant. */
    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    protected OrgSubscription() {
        // JPA
    }

    static OrgSubscription of(UUID orgId, UUID planId) {
        OrgSubscription subscription = new OrgSubscription();
        subscription.orgId = orgId;
        subscription.planId = planId;
        subscription.status = Status.ACTIVE;
        return subscription;
    }

    /** A fresh subscription that starts life as a paid-plan trial ending at {@code trialEndsAt}. */
    static OrgSubscription trial(UUID orgId, UUID planId, Instant trialEndsAt) {
        OrgSubscription subscription = new OrgSubscription();
        subscription.orgId = orgId;
        subscription.planId = planId;
        subscription.status = Status.TRIALING;
        subscription.trialEndsAt = trialEndsAt;
        return subscription;
    }

    void changePlan(UUID newPlanId) {
        this.planId = newPlanId;
        this.status = Status.ACTIVE; // a fresh assignment always restores good standing
        this.trialEndsAt = null;     // ...and ends any trial: this is now a real, paid plan
    }

    /** Put an existing subscription onto a paid-plan trial (a restart lands here too). */
    void startTrial(UUID trialPlanId, Instant endsAt) {
        this.planId = trialPlanId;
        this.status = Status.TRIALING;
        this.trialEndsAt = endsAt;
    }

    /** The trial lapsed unpaid: read-only until a plan is assigned or a payment lands. */
    void pause() {
        this.status = Status.PAUSED;
    }

    void markStatus(Status newStatus) {
        this.status = newStatus;
        if (newStatus == Status.ACTIVE) {
            this.trialEndsAt = null; // a payment/conversion clears the trial and un-pauses
        }
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getPlanId() {
        return planId;
    }

    Status getStatus() {
        return status;
    }

    Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    Instant getTrialEndsAt() {
        return trialEndsAt;
    }
}
