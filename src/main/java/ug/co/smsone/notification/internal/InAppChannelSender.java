package ug.co.smsone.notification.internal;

import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;

/** In-app delivery: persists a notification the recipient reads via {@code /api/v1/notifications}. */
@Component
class InAppChannelSender implements NotificationChannelSender {

    private final InAppNotificationRepository repository;

    InAppChannelSender(InAppNotificationRepository repository) {
        this.repository = repository;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public void send(NotificationMessage message) {
        repository.save(InAppNotification.create(message.address(), message.subject(), message.body()));
    }
}
