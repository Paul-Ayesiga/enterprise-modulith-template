package ug.co.smsone.notification.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.shared.persistence.BaseEntity;

/**
 * An in-app notification addressed to a PERSON, readable via the REST API.
 *
 * <p>{@code personId} is a soft ref to {@code person.id} with no FK — identity is another module
 * (AGENTS §1). The column was {@code recipient varchar(150)} holding a Keycloak subject behind a
 * neutral name; V8 renamed it and this field says the same thing the column now does.
 */
@Entity
@Table(name = "in_app_notification")
class InAppNotification extends BaseEntity {

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(columnDefinition = "text")
    private String body;

    @Column
    private Instant readAt;

    protected InAppNotification() {
        // JPA
    }

    static InAppNotification create(UUID personId, String subject, String body) {
        InAppNotification notification = new InAppNotification();
        notification.personId = personId;
        notification.subject = subject;
        notification.body = body;
        return notification;
    }

    boolean isRead() {
        return readAt != null;
    }

    String getSubject() {
        return subject;
    }

    String getBody() {
        return body;
    }
}
