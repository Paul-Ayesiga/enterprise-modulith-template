package ug.co.smsone.notification;

/**
 * Extension point for a delivery channel. Implement it, expose it as a Spring bean, and the
 * dispatcher will route every {@link Recipient} whose {@link #channel()} matches to it. Exactly one
 * sender per channel is expected; a second one for the same channel is ignored with a warning.
 *
 * <p>Adding a real SMS/Slack/push provider is a drop-in: no change to the module's core.
 */
public interface NotificationChannelSender {

    /** The channel this sender handles. */
    NotificationChannel channel();

    /**
     * Deliver the message. Throw any {@link RuntimeException} to signal failure — the dispatcher
     * records it against the recipient and moves on to the next.
     */
    void send(NotificationMessage message);
}
