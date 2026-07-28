package ug.co.smsone.notification.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import ug.co.smsone.shared.persistence.BaseEntity;

/** An in-app notification addressed to a user (by immutable Keycloak subject), readable via the REST API. */
@Entity
@Table(name = "in_app_notification")
class InAppNotification extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String recipient;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(columnDefinition = "text")
    private String body;

    @Column
    private Instant readAt;

    protected InAppNotification() {
        // JPA
    }

    static InAppNotification create(String recipient, String subject, String body) {
        InAppNotification notification = new InAppNotification();
        notification.recipient = recipient;
        notification.subject = subject;
        notification.body = body;
        return notification;
    }

    boolean isRead() {
        return readAt != null;
    }

    String getRecipient() {
        return recipient;
    }

    String getSubject() {
        return subject;
    }

    String getBody() {
        return body;
    }

    Instant getReadAt() {
        return readAt;
    }
}
