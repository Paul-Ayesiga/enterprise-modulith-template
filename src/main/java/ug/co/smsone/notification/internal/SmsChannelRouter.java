package ug.co.smsone.notification.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * The SMS channel, org-aware: WHICH provider sends is the recipient org's integration-hub choice
 * (org override → platform default, both database rows with encrypted settings), resolved per
 * message from the {@code orgId} the delivery row carries — env config is only the last fallback
 * (Speeda). An org configured for a provider this build doesn't ship throws a clear error naming
 * the known adapters, so the delivery dead-letters loudly instead of silently using the wrong
 * gateway. Active when {@code app.notification.sms.stub=false}; the logging stub is the dev default.
 */
@Component
@ConditionalOnProperty(name = "app.notification.sms.stub", havingValue = "false")
class SmsChannelRouter implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SmsChannelRouter.class);

    private final Map<String, SmsGateway> gateways;
    private final ObjectProvider<Integrations> integrations;

    SmsChannelRouter(List<SmsGateway> gateways, ObjectProvider<Integrations> integrations) {
        this.gateways = gateways.stream()
                .collect(Collectors.toUnmodifiableMap(g -> g.provider().toLowerCase(), Function.identity()));
        this.integrations = integrations;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public void send(NotificationMessage message) {
        UUID orgId = orgId(message);
        Integrations hub = integrations.getIfAvailable();
        Integrations.ResolvedIntegration resolved = hub == null ? null
                : hub.resolve(orgId, Integrations.Kind.SMS_PROVIDER).orElse(null);
        if (resolved == null) {
            // No hub choice anywhere — the Speeda adapter's env fallback carries dev/default sending.
            gateway(SpeedaMobileSmsSender.PROVIDER).send(message, Map.of());
            return;
        }
        SmsGateway gateway = gateway(resolved.provider());
        log.debug("SMS routed via '{}' for org {}", resolved.provider(), orgId);
        gateway.send(message, resolved.settings());
    }

    private SmsGateway gateway(String provider) {
        SmsGateway gateway = provider == null ? null : gateways.get(provider.toLowerCase());
        if (gateway == null) {
            throw new IllegalStateException("No SMS adapter for provider '" + provider
                    + "' — known: " + String.join(", ", gateways.keySet()));
        }
        return gateway;
    }

    private static UUID orgId(NotificationMessage message) {
        String value = message.metadata().get("orgId");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
