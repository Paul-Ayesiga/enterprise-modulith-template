package ug.co.smsone.settings.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.settings.SettingChanged;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * A platform key/value setting. Public — unlike the reference entities in {@code organization} —
 * because two shared-kernel ITs are built on it as their guinea pig: {@code SoftDeleteTest} uses it
 * as the representative soft-deletable aggregate, and {@code AuditRecordingTest} drives real audit
 * capture through it. A parallel test fixture would duplicate exactly this shape.
 */
@Entity
@Table(name = "setting")
@SQLDelete(sql = "update setting set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
public class Setting extends SoftDeletableEntity {

    @Column(name = "setting_key", nullable = false, length = 150, updatable = false)
    private String key;

    @Column(name = "setting_value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(columnDefinition = "text")
    private String description;

    protected Setting() {
        // JPA
    }

    public static Setting create(String key, String value, String description) {
        Setting setting = new Setting();
        setting.key = key;
        setting.change(value, description);
        return setting;
    }

    public void change(String value, String description) {
        if (value.equals(this.value) && java.util.Objects.equals(description, this.description)) {
            return; // idempotent no-op: no spurious event fan-out, no x → x audit row (AGENTS §6)
        }
        this.value = value;
        this.description = description;
        registerEvent(new SettingChanged(key, value, java.time.Instant.now()));
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }
}
