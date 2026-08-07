package ug.co.smsone.notification.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;
import ug.co.smsone.notification.Recipient;

/**
 * In-app delivery: persists a notification the recipient reads via {@code /api/v1/notifications}.
 *
 * <p>The one channel whose {@code address} is an identity rather than a place, so this is the one
 * sender that parses it back to the {@code person.id} {@link Recipient#inApp(java.util.UUID)} rendered.
 * A malformed address means an enqueued row that can never be delivered, so it fails loudly here
 * rather than persisting a notification addressed to nobody.
 */
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
        repository.save(InAppNotification.create(personId(message.address()), message.subject(), message.body()));
    }

    private static UUID personId(String address) {
        try {
            return UUID.fromString(address);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException(
                    "IN_APP address must be a person.id; got '" + address + "'.", ex);
        }
    }
}
