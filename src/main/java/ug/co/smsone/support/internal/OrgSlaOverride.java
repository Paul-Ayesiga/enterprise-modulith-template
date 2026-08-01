package ug.co.smsone.support.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import ug.co.smsone.shared.persistence.BaseEntity;

/**
 * One org's SLA target for one priority, overriding the seeded {@link SlaPolicy}. Config data (not
 * soft-deletable): clearing an override is a real delete that falls the org back to the default.
 */
@Entity
@Table(name = "org_sla_override")
class OrgSlaOverride extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false, length = 2, updatable = false)
    private String priority;

    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    protected OrgSlaOverride() {
        // JPA
    }

    static OrgSlaOverride of(UUID orgId, String priority, int firstResponseMinutes, int resolutionMinutes) {
        OrgSlaOverride override = new OrgSlaOverride();
        override.orgId = orgId;
        override.priority = priority;
        override.firstResponseMinutes = firstResponseMinutes;
        override.resolutionMinutes = resolutionMinutes;
        return override;
    }

    void retarget(int newFirstResponseMinutes, int newResolutionMinutes) {
        this.firstResponseMinutes = newFirstResponseMinutes;
        this.resolutionMinutes = newResolutionMinutes;
    }

    UUID getOrgId() {
        return orgId;
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
