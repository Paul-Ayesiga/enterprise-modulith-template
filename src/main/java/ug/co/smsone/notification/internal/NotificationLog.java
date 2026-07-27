package ug.co.smsone.notification.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.shared.persistence.BaseEntity;

/** Audit trail: one row per channel delivery attempt (sent or failed). Owned by this module. */
@Entity
@Table(name = "notification_log")
class NotificationLog extends BaseEntity {

    private static final int MAX_ERROR = 1000;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false, length = 320)
    private String recipient;

    @Column(nullable = false, length = 255)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(columnDefinition = "text")
    private String error;

    protected NotificationLog() {
        // JPA
    }

    private NotificationLog(NotificationChannel channel, String recipient, String subject,
            NotificationStatus status, String error) {
        this.channel = channel;
        this.recipient = recipient;
        this.subject = truncate(subject, 255);
        this.status = status;
        this.error = truncate(error, MAX_ERROR);
    }

    static NotificationLog sent(NotificationChannel channel, String recipient, String subject) {
        return new NotificationLog(channel, recipient, subject, NotificationStatus.SENT, null);
    }

    static NotificationLog failed(NotificationChannel channel, String recipient, String subject, String error) {
        return new NotificationLog(channel, recipient, subject, NotificationStatus.FAILED, error);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    NotificationChannel getChannel() {
        return channel;
    }

    String getRecipient() {
        return recipient;
    }

    NotificationStatus getStatus() {
        return status;
    }

    String getError() {
        return error;
    }
}
