package ug.co.smsone.notification.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ug.co.smsone.integration.Integrations;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;

/**
 * Real SMS delivery over the Speeda Mobile Web SMS API (v1.13): {@code POST {base}/api/SendSMS} with
 * {@code api_id / api_password / sms_type / encoding / sender_id / phonenumber / textmessage}; the
 * gateway answers {@code {message_id, status: S|F, remarks}}. Active when
 * {@code app.notification.sms.stub=false} (the dev default keeps the logging stub).
 *
 * <p>Credentials resolve through the integration hub first — an {@code SMS_PROVIDER} integration with
 * provider {@code speedamobile} (settings {@code apiId/apiPassword/senderId}, org override wins over
 * platform default) — falling back to the static {@link SpeedaSmsProperties} (env {@code SPEEDA_*}).
 *
 * <p>Wire rules from the spec: the phone number carries no {@code +} (country code + number, digits
 * only — normalization strips formatting); {@code encoding} auto-upgrades to {@code U} (unicode) when
 * the text needs more than the GSM Latin range. A gateway refusal ({@code status != S}) throws — the
 * dispatcher records the failure and retries with backoff like any channel error.
 */
@Component
@ConditionalOnProperty(name = "app.notification.sms.stub", havingValue = "false")
class SpeedaMobileSmsSender implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SpeedaMobileSmsSender.class);
    static final String PROVIDER = "speedamobile";

    private final RestClient restClient;
    private final SpeedaSmsProperties properties;
    private final ObjectProvider<Integrations> integrations;

    SpeedaMobileSmsSender(RestClient speedaRestClient, SpeedaSmsProperties properties,
            ObjectProvider<Integrations> integrations) {
        this.restClient = speedaRestClient;
        this.properties = properties;
        this.integrations = integrations;
    }

    record SpeedaResponse(Object message_id, String status, String remarks) {
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public void send(NotificationMessage message) {
        Credentials credentials = resolveCredentials();
        String phone = normalizePhone(message.address());
        String text = message.body() == null || message.body().isBlank()
                ? message.subject() : message.body();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("api_id", credentials.apiId());
        request.put("api_password", credentials.apiPassword());
        request.put("sms_type", credentials.smsType());
        request.put("encoding", encodingFor(text, credentials.encoding()));
        request.put("sender_id", credentials.senderId());
        request.put("phonenumber", phone);
        request.put("textmessage", text);

        SpeedaResponse response = restClient.post()
                .uri(credentials.baseUrl() + "/api/SendSMS")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(SpeedaResponse.class);
        if (response == null || !"S".equalsIgnoreCase(response.status())) {
            // The dispatcher catches, records the failure on the delivery row, and retries with backoff.
            throw new IllegalStateException("Speeda Mobile refused the SMS: "
                    + (response == null ? "empty response" : response.remarks()));
        }
        log.info("SMS submitted via Speeda Mobile: to={} messageId={}", phone, response.message_id());
    }

    private record Credentials(String baseUrl, String apiId, String apiPassword, String senderId,
            String smsType, String encoding) {
    }

    /** Integration hub (provider {@code speedamobile}) first; static env config as the fallback. */
    private Credentials resolveCredentials() {
        Integrations hub = integrations.getIfAvailable();
        if (hub != null) {
            var resolved = hub.resolve(null, Integrations.Kind.SMS_PROVIDER)
                    .filter(integration -> PROVIDER.equalsIgnoreCase(integration.provider()))
                    .orElse(null);
            if (resolved != null) {
                Map<String, String> settings = resolved.settings();
                String apiId = settings.get("apiId");
                String apiPassword = settings.get("apiPassword");
                String senderId = settings.get("senderId");
                if (apiId != null && apiPassword != null && senderId != null) {
                    return new Credentials(
                            settings.getOrDefault("baseUrl", properties.baseUrl()),
                            apiId, apiPassword, senderId,
                            settings.getOrDefault("smsType", properties.smsType()),
                            settings.getOrDefault("encoding", properties.encoding()));
                }
                log.warn("SMS integration '{}' is missing apiId/apiPassword/senderId — falling back to env config",
                        PROVIDER);
            }
        }
        if (!properties.configured()) {
            throw new IllegalStateException("No Speeda Mobile credentials: configure the SMS_PROVIDER "
                    + "integration (provider 'speedamobile') or set SPEEDA_API_ID/SPEEDA_API_PASSWORD/SPEEDA_SENDER_ID.");
        }
        return new Credentials(properties.baseUrl(), properties.apiId(), properties.apiPassword(),
                properties.senderId(), properties.smsType(), properties.encoding());
    }

    /** Spec: no leading {@code +}; country code + number, digits only. */
    static String normalizePhone(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("SMS recipient has no phone number");
        }
        String digits = address.replaceAll("[\\s()./-]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (!digits.matches("\\d{9,15}")) {
            throw new IllegalStateException("Not a sendable phone number: " + address);
        }
        return digits;
    }

    /** Auto-upgrade to unicode when the text leaves the basic Latin range the T encoding carries. */
    static String encodingFor(String text, String configured) {
        if (!"T".equalsIgnoreCase(configured)) {
            return configured; // an explicit U / FS / UFS choice is respected
        }
        return text != null && text.chars().anyMatch(c -> c > 0x7E) ? "U" : configured;
    }
}
