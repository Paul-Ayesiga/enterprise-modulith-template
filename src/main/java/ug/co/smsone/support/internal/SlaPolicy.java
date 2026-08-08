package ug.co.smsone.support.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ug.co.smsone.shared.persistence.BaseEntity;

/** SLA targets for one priority — seeded reference data, not soft-deletable. */
@Entity
@Table(name = "sla_policy", schema = "platform")
class SlaPolicy extends BaseEntity {

    @Column(nullable = false, length = 2)
    private String priority;

    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    protected SlaPolicy() {
        // JPA
    }

    static SlaPolicy of(String priority, int firstResponseMinutes, int resolutionMinutes) {
        SlaPolicy policy = new SlaPolicy();
        policy.priority = priority;
        policy.firstResponseMinutes = firstResponseMinutes;
        policy.resolutionMinutes = resolutionMinutes;
        return policy;
    }

    String getPriority() {
        return priority;
    }

    int getFirstResponseMinutes() {
        return firstResponseMinutes;
    }

    int getResolutionMinutes() {
        return resolutionMinutes;
    }
}
