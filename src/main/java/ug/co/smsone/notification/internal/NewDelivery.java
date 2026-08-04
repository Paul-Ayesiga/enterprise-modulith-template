package ug.co.smsone.notification.internal;

import java.util.UUID;
import ug.co.smsone.notification.NotificationChannel;

/** A delivery to be enqueued (one recipient, one channel; orgId carries the tenant context, nullable). */
record NewDelivery(NotificationChannel channel, String recipient, String subject, String body, UUID orgId) {
}
