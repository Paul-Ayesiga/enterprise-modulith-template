package ug.co.smsone.notification.internal;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;

/** Resolves a {@link NotificationChannel} to its (single) registered sender bean. */
@Component
class ChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);

    private final Map<NotificationChannel, NotificationChannelSender> senders;

    ChannelRegistry(List<NotificationChannelSender> channelSenders) {
        Map<NotificationChannel, NotificationChannelSender> registry = new EnumMap<>(NotificationChannel.class);
        for (NotificationChannelSender sender : channelSenders) {
            NotificationChannelSender existing = registry.putIfAbsent(sender.channel(), sender);
            if (existing != null) {
                log.warn("Multiple senders for channel {}: keeping {}, ignoring {}", sender.channel(),
                        existing.getClass().getSimpleName(), sender.getClass().getSimpleName());
            }
        }
        this.senders = registry;
        log.info("Notification channels registered: {}", registry.keySet());
    }

    NotificationChannelSender get(NotificationChannel channel) {
        return senders.get(channel);
    }
}
