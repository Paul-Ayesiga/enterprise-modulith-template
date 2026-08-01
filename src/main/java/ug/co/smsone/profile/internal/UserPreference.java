package ug.co.smsone.profile.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

/** One small per-user setting — the idempotency-key species: composite PK, no aggregate lifecycle. */
@Entity
@Table(name = "user_preference")
@IdClass(UserPreference.Key.class)
class UserPreference {

    @Id
    @Column(nullable = false, length = 64)
    private String subject;

    @Id
    @Column(name = "pref_key", nullable = false, length = 100)
    private String prefKey;

    @Column(name = "pref_value", nullable = false, length = 500)
    private String prefValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    record Key(String subject, String prefKey) implements Serializable {
    }

    protected UserPreference() {
        // JPA
    }

    static UserPreference of(String subject, String key, String value, Instant now) {
        UserPreference preference = new UserPreference();
        preference.subject = subject;
        preference.prefKey = key;
        preference.prefValue = value;
        preference.updatedAt = now;
        return preference;
    }

    void change(String value, Instant now) {
        this.prefValue = value;
        this.updatedAt = now;
    }

    String getPrefKey() {
        return prefKey;
    }

    String getPrefValue() {
        return prefValue;
    }
}
