package ug.co.smsone.settings.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import ug.co.smsone.settings.FeatureFlagChanged;
import ug.co.smsone.shared.persistence.AggregateRoot;

@Entity
@Table(name = "feature_flag")
public class FeatureFlag extends AggregateRoot {

    @Column(name = "flag_key", nullable = false, unique = true, length = 150, updatable = false)
    private String key;

    @Column(nullable = false)
    private boolean enabled;

    @Column(columnDefinition = "text")
    private String description;

    protected FeatureFlag() {
        // JPA
    }

    public static FeatureFlag create(String key, boolean enabled, String description) {
        FeatureFlag flag = new FeatureFlag();
        flag.key = key;
        flag.toggle(enabled, description);
        return flag;
    }

    public void toggle(boolean enabled, String description) {
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
