package ug.co.smsone.notification.internal;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;

/**
 * Dispatches a {@link NotificationRequest} to the registered {@link NotificationChannelSender} for
 * each recipient's channel, recording every attempt in {@code notification_log}. Best-effort: one
 * recipient/channel failure never aborts the rest.
 */
@Service
class NotificationService implements Notifications {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final Map<NotificationChannel, NotificationChannelSender> senders;
    private final NotificationLogRepository logs;
    private final NotificationProperties properties;

    NotificationService(List<NotificationChannelSender> channelSenders,
            NotificationLogRepository logs, NotificationProperties properties) {
        Map<NotificationChannel, NotificationChannelSender> registry = new EnumMap<>(NotificationChannel.class);
        for (NotificationChannelSender sender : channelSenders) {
            NotificationChannelSender existing = registry.putIfAbsent(sender.channel(), sender);
            if (existing != null) {
                log.warn("Multiple senders for channel {}: keeping {}, ignoring {}", sender.channel(),
                        existing.getClass().getSimpleName(), sender.getClass().getSimpleName());
            }
        }
        this.senders = registry;
        this.logs = logs;
        this.properties = properties;
        log.info("Notification channels registered: {}", registry.keySet());
    }

    @Override
    public void dispatch(NotificationRequest request) {
        for (Recipient recipient : request.recipients()) {
            deliver(recipient, request);
        }
    }

    private void deliver(Recipient recipient, NotificationRequest request) {
        NotificationChannelSender sender = senders.get(recipient.channel());
        if (sender == null) {
            logs.save(NotificationLog.failed(recipient.channel(), recipient.address(), request.subject(),
                    "No sender registered for channel " + recipient.channel()));
            log.warn("No sender registered for channel {} (recipient {})", recipient.channel(), recipient.address());
            return;
        }
        try {
            sender.send(new NotificationMessage(recipient.address(), request.subject(),
                    request.body(), request.metadata()));
            logs.save(NotificationLog.sent(recipient.channel(), recipient.address(), request.subject()));
        } catch (RuntimeException ex) {
            // Errors stay server-side (log + audit row); never surfaced to callers/clients.
            logs.save(NotificationLog.failed(recipient.channel(), recipient.address(), request.subject(),
                    ex.getMessage()));
            log.warn("Delivery via {} to {} failed: {}", recipient.channel(), recipient.address(), ex.toString());
        }
    }

    @Override
    public void notifyAdmins(String subject, String body) {
        List<Recipient> recipients = properties.admins().stream()
                .flatMap(admin -> Stream.of(Recipient.email(admin.email()), Recipient.inApp(admin.username())))
                .toList();
        if (recipients.isEmpty()) {
            log.debug("notifyAdmins skipped: no administrators configured");
            return;
        }
        dispatch(NotificationRequest.of(subject, body, recipients));
    }
}
