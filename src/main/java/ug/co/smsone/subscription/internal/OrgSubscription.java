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

    enum Status { ACTIVE, TRIALING, PAST_DUE, CANCELLED }

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

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

    void changePlan(UUID newPlanId) {
        this.planId = newPlanId;
        this.status = Status.ACTIVE; // a fresh assignment always restores good standing
    }

    void markStatus(Status newStatus) {
        this.status = newStatus;
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
}
