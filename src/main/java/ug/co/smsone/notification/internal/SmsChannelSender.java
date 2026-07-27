package ug.co.smsone.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;

/**
 * DEV STUB for SMS — logs the message instead of sending it. No SMS gateway is wired.
 *
 * <p>To send real SMS, register another {@link NotificationChannelSender} bean for
 * {@link NotificationChannel#SMS} (e.g. Africa's Talking or Twilio) and set
 * {@code app.notification.sms.stub=false} to switch this stub off.
 */
@Component
@ConditionalOnProperty(name = "app.notification.sms.stub", havingValue = "true", matchIfMissing = true)
class SmsChannelSender implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SmsChannelSender.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public void send(NotificationMessage message) {
        log.info("[SMS stub] to={} subject={} (no gateway wired — see SmsChannelSender)",
                message.address(), message.subject());
    }
}
