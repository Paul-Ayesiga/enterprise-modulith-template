package ug.co.smsone.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.identity.UserActivated;
import ug.co.smsone.identity.UserProvisioned;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** Local projection of a Keycloak user + provisioning lifecycle. {@code subject} == Keycloak {@code sub}. */
@Entity
@Table(name = "app_user")
@SQLDelete(sql = "update app_user set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class User extends SoftDeletableEntity {

    @Column(nullable = false, updatable = false, length = 64)
    private String subject;

    @Column(nullable = false, length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProvisioningStatus status;

    @Column(name = "provisioned_at", nullable = false, updatable = false)
    private Instant provisionedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    protected User() {
        // JPA
    }

    static User invited(String subject, String email, Instant when) {
        User user = new User();
        user.subject = subject;
        user.email = email;
        user.status = ProvisioningStatus.INVITED;
        user.provisionedAt = when;
        user.registerEvent(new UserProvisioned(subject, email, when));
        return user;
    }

    /** First API hit after admin provisioning — flips INVITED → ACTIVE. Not JIT (the row pre-existed). */
    void activate(Instant when) {
        if (status == ProvisioningStatus.INVITED) {
            this.status = ProvisioningStatus.ACTIVE;
            this.activatedAt = when;
            registerEvent(new UserActivated(subject, when));
        }
    }

    void disable() {
        this.status = ProvisioningStatus.DISABLED;
    }

    String getSubject() {
        return subject;
    }

    String getEmail() {
        return email;
    }

    ProvisioningStatus getStatus() {
        return status;
    }

    Instant getActivatedAt() {
        return activatedAt;
    }
}
