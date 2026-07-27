package ug.co.smsone.notification;

/**
 * The notification module's public facade. Other modules depend on this interface (never on
 * internals) to send notifications, either directly or from their own event listeners.
 */
public interface Notifications {

    /**
     * Deliver a request across every recipient's channel. Best-effort and non-throwing: each
     * delivery is attempted and its outcome recorded independently.
     */
    void dispatch(NotificationRequest request);

    /** Convenience: notify the configured administrators (email + in-app) of an operational event. */
    void notifyAdmins(String subject, String body);
}
