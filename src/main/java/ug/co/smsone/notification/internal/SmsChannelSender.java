package ug.co.smsone.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ug.co.smsone.integration.Integrations;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;

/**
 * DEV STUB for SMS — logs the message instead of sending it. It DOES consult the integration hub
 * for the configured SMS provider (platform-default scope; a real per-recipient sender would
 * resolve the recipient's org), demonstrating the {@code Integrations} resolution seam a real
 * gateway would use to pick up its credentials.
 *
 * <p>To send real SMS, register another {@link NotificationChannelSender} bean for
 * {@link NotificationChannel#SMS} and set {@code app.notification.sms.stub=false}.
 */
@Component
@ConditionalOnProperty(name = "app.notification.sms.stub", havingValue = "true", matchIfMissing = true)
class SmsChannelSender implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SmsChannelSender.class);

    private final ObjectProvider<Integrations> integrations;

    SmsChannelSender(ObjectProvider<Integrations> integrations) {
        this.integrations = integrations;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public void send(NotificationMessage message) {
        String provider = integrations.getIfAvailable() == null ? "none"
                : integrations.getObject().resolve(null, Integrations.Kind.SMS_PROVIDER)
                        .map(Integrations.ResolvedIntegration::provider).orElse("none (unconfigured)");
        log.info("[SMS stub] to={} subject={} provider={} (no gateway wired — see SmsChannelSender)",
                message.address(), message.subject(), provider);
    }
}
