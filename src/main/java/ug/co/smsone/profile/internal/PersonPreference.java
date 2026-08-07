package ug.co.smsone.profile.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * One small per-person setting — the idempotency-key species: composite PK, no aggregate lifecycle,
 * because a preference has no independent existence whose deletion is worth recording.
 *
 * <p>Half of that primary key used to be a Keycloak {@code sub} referencing nothing at all, so rows
 * could outlive — or precede — any account they claimed to belong to. It is {@code person.id} now: a
 * soft ref with no FK, on the same terms as {@link PersonProfile} (V28).
 */
@Entity
@Table(name = "person_preference")
@IdClass(PersonPreference.Key.class)
class PersonPreference {

    @Id
    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Id
    @Column(name = "pref_key", nullable = false, length = 100)
    private String prefKey;

    @Column(name = "pref_value", nullable = false, length = 500)
    private String prefValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    record Key(UUID personId, String prefKey) implements Serializable {
    }

    protected PersonPreference() {
        // JPA
    }

    static PersonPreference of(UUID personId, String key, String value, Instant now) {
        PersonPreference preference = new PersonPreference();
        preference.personId = personId;
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
