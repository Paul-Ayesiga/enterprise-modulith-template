package ug.co.smsone.settings.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.settings.FeatureFlagChanged;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

@Entity
@Table(name = "feature_flag")
@SQLDelete(sql = "update feature_flag set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
public class FeatureFlag extends SoftDeletableEntity {

    @Column(name = "flag_key", nullable = false, length = 150, updatable = false)
    private String key;

    @Column(nullable = false)
    private boolean enabled;

    @Column(columnDefinition = "text")
    private String description;

    /** Percentage rollout (0–100) while enabled; null = all-or-nothing. Bucketed per org, sticky. */
    @Column
    private Integer percentage;

    protected FeatureFlag() {
        // JPA
    }

    public static FeatureFlag create(String key, boolean enabled, String description) {
        // Fields set directly, not via toggle(): a flag created disabled matches the boolean's
        // default, and toggle's no-op guard would silently swallow the creation event.
        FeatureFlag flag = new FeatureFlag();
        flag.key = key;
        flag.enabled = enabled;
        flag.description = description;
        flag.registerEvent(new FeatureFlagChanged(key, enabled, Instant.now()));
        return flag;
    }

    public void rollout(Integer percentage) {
        this.percentage = percentage;
    }

    public Integer getPercentage() {
        return percentage;
    }

    public void toggle(boolean enabled, String description) {
        if (this.enabled == enabled && java.util.Objects.equals(description, this.description)) {
            return; // idempotent no-op: a repeated PUT must not re-notify admins or re-audit (AGENTS §6)
        }
        this.enabled = enabled;
        this.description = description;
        registerEvent(new FeatureFlagChanged(key, enabled, Instant.now()));
    }

    public String getKey() {
        return key;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDescription() {
        return description;
    }
}
