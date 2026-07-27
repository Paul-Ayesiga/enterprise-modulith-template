package ug.co.smsone.notification.internal;

import ug.co.smsone.notification.NotificationChannel;

/** A delivery to be enqueued (one recipient, one channel). */
record NewDelivery(NotificationChannel channel, String recipient, String subject, String body) {
}
