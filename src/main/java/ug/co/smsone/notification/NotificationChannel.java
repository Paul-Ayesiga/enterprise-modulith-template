package ug.co.smsone.notification;

/**
 * Delivery channels. Each has (at most) one registered {@link NotificationChannelSender}; a channel
 * with no sender is reported as a failed delivery rather than silently dropped.
 */
public enum NotificationChannel {
    EMAIL,
    SMS,
    IN_APP,
    SLACK,
    WEBHOOK
}
