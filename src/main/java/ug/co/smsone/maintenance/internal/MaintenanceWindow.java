package ug.co.smsone.maintenance.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** A time-boxed maintenance window, platform-wide (orgId null) or for one org. */
@Entity
@Table(name = "maintenance_window")
@SQLDelete(sql = "update maintenance_window set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class MaintenanceWindow extends SoftDeletableEntity {

    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false, length = 10)
    private String mode; // ANNOUNCE | RESTRICT

    @Column(nullable = false, length = 300)
    private String message;

    protected MaintenanceWindow() {
        // JPA
    }

    static MaintenanceWindow create(UUID orgId, Instant startsAt, Instant endsAt, String mode, String message) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.orgId = orgId;
        window.startsAt = startsAt;
        window.endsAt = endsAt;
        window.mode = mode;
        window.message = message;
        return window;
    }

    boolean restricts() {
        return "RESTRICT".equals(mode);
    }

    UUID getOrgId() {
        return orgId;
    }

    Instant getStartsAt() {
        return startsAt;
    }

    Instant getEndsAt() {
        return endsAt;
    }

    String getMode() {
        return mode;
    }

    String getMessage() {
        return message;
    }
}
