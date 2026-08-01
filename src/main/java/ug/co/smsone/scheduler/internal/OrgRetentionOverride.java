package ug.co.smsone.scheduler.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import ug.co.smsone.shared.persistence.BaseEntity;

/**
 * One org's retention (in days) for one scope, overriding the platform default. Config data (not
 * soft-deletable): clearing it is a real delete that falls the org back to the platform default.
 */
@Entity
@Table(name = "org_retention_override")
class OrgRetentionOverride extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false, length = 40, updatable = false)
    private String scope;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    protected OrgRetentionOverride() {
        // JPA
    }

    static OrgRetentionOverride of(UUID orgId, String scope, int retentionDays) {
        OrgRetentionOverride override = new OrgRetentionOverride();
        override.orgId = orgId;
        override.scope = scope;
        override.retentionDays = retentionDays;
        return override;
    }

    void retain(int days) {
        this.retentionDays = days;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getScope() {
        return scope;
    }

    int getRetentionDays() {
        return retentionDays;
    }
}
