package ug.co.smsone.settings.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ug.co.smsone.settings.SettingChanged;
import ug.co.smsone.shared.persistence.AggregateRoot;

@Entity
@Table(name = "setting")
public class Setting extends AggregateRoot {

    @Column(name = "setting_key", nullable = false, unique = true, length = 150, updatable = false)
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
        this.value = value;
        this.description = description;
        registerEvent(new SettingChanged(key, value));
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
