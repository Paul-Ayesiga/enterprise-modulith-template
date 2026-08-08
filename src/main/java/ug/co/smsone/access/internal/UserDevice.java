package ug.co.smsone.access.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * A device a person signs in from. Revocation is soft-delete: the row stays as the trail.
 *
 * <p>{@code personId} is a soft ref to {@code person.id} with no FK — person (V10) is the identity
 * module and this table is not (V31 says so in its own header).
 */
@Entity
@Table(name = "user_device", schema = "platform")
@SQLDelete(sql = "update platform.user_device set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class UserDevice extends SoftDeletableEntity {

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(nullable = false, updatable = false, length = 100)
    private String fingerprint;

    @Column(name = "push_token", length = 300)
    private String pushToken;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected UserDevice() {
        // JPA
    }

    static UserDevice register(UUID personId, String name, String kind, String fingerprint, String pushToken) {
        UserDevice device = new UserDevice();
        device.personId = personId;
        device.name = name;
        device.kind = kind;
        device.fingerprint = fingerprint;
        device.pushToken = pushToken;
        return device;
    }

    void update(String name, String pushToken) {
        this.name = name;
        this.pushToken = pushToken;
    }


    UUID getPersonId() {
        return personId;
    }

    String getName() {
        return name;
    }

    String getKind() {
        return kind;
    }

    String getFingerprint() {
        return fingerprint;
    }

    String getPushToken() {
        return pushToken;
    }


    Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
