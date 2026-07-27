/**
 * Notification module: multi-channel, pluggable delivery of messages to recipients.
 *
 * <p>Channels are an open extension point — implement
 * {@link ug.co.smsone.notification.NotificationChannelSender} and expose it as a Spring bean to add
 * one. Built-ins: email (SMTP), in-app (persisted + read via REST), webhook and Slack (HTTP), and an
 * SMS dev-stub. Other modules publish domain events or call
 * {@link ug.co.smsone.notification.Notifications}; internals live under {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notification")
package ug.co.smsone.notification;
