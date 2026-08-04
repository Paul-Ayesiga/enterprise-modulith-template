package ug.co.smsone.settings.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A hard per-org answer for one flag — beats the global value and any percentage. */
@Entity
@Table(name = "feature_flag_org_override")
class FeatureFlagOrgOverride {

    @Id
    private UUID id;

    @Column(name = "flag_key", nullable = false, length = 150)
    private String flagKey;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FeatureFlagOrgOverride() {
        // JPA
    }

    static FeatureFlagOrgOverride of(String flagKey, UUID orgId, boolean enabled, Instant now) {
        FeatureFlagOrgOverride override = new FeatureFlagOrgOverride();
        override.id = UUID.randomUUID();
        override.flagKey = flagKey;
        override.orgId = orgId;
        override.enabled = enabled;
        override.createdAt = now;
        return override;
    }

    void set(boolean enabled) {
        this.enabled = enabled;
    }

    boolean isEnabled() {
        return enabled;
    }
}
