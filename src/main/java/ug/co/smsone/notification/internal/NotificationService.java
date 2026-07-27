package ug.co.smsone.notification.internal;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;

/**
 * Public entry point. {@code dispatch} does not send — it durably enqueues one delivery per
 * recipient/channel and returns immediately (non-blocking); the {@link NotificationDeliveryWorker}
 * fans them out asynchronously. This is what lets a single call target thousands of recipients
 * without blocking the caller or holding resources.
 */
@Service
class NotificationService implements Notifications {

    private final NotificationDeliveryQueue queue;
    private final NotificationProperties properties;

    NotificationService(NotificationDeliveryQueue queue, NotificationProperties properties) {
        this.queue = queue;
        this.properties = properties;
    }

    @Override
    public void dispatch(NotificationRequest request) {
        if (request.recipients().isEmpty()) {
            return;
        }
        List<NewDelivery> deliveries = request.recipients().stream()
                .map(recipient -> new NewDelivery(recipient.channel(), recipient.address(),
                        request.subject(), request.body()))
                .toList();
        queue.enqueue(deliveries, properties.delivery().maxAttempts());
    }

    @Override
    public void notifyAdmins(String subject, String body) {
        List<Recipient> recipients = properties.admins().stream()
                .flatMap(admin -> Stream.of(Recipient.email(admin.email()), Recipient.inApp(admin.username())))
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        dispatch(new NotificationRequest(subject, body, recipients, Map.of()));
    }
}
