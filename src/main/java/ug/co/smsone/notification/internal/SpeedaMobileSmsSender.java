package ug.co.smsone.notification.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ug.co.smsone.notification.NotificationMessage;

/**
 * Real SMS delivery over the Speeda Mobile Web SMS API (v1.13): {@code POST {base}/api/SendSMS} with
 * {@code api_id / api_password / sms_type / encoding / sender_id / phonenumber / textmessage}; the
 * gateway answers {@code {message_id, status: S|F, remarks}}. Active when
 * {@code app.notification.sms.stub=false} (the dev default keeps the logging stub).
 *
 * <p>One adapter behind the {@link SmsChannelRouter}: the router resolves the org's provider choice
 * and hands over that integration's decrypted settings ({@code apiId/apiPassword/senderId}, optional
 * {@code baseUrl/smsType/encoding}); missing keys fall back to the static {@link SpeedaSmsProperties}
 * (env {@code SPEEDA_*}).
 *
 * <p>Wire rules from the spec: the phone number carries no {@code +} (country code + number, digits
 * only — normalization strips formatting); {@code encoding} auto-upgrades to {@code U} (unicode) when
 * the text needs more than the GSM Latin range. A gateway refusal ({@code status != S}) throws — the
 * dispatcher records the failure and retries with backoff like any channel error.
 */
@Component
class SpeedaMobileSmsSender implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(SpeedaMobileSmsSender.class);
    static final String PROVIDER = "speedamobile";

    private final RestClient restClient;
    private final SpeedaSmsProperties properties;

    SpeedaMobileSmsSender(RestClient speedaRestClient, SpeedaSmsProperties properties) {
        this.restClient = speedaRestClient;
        this.properties = properties;
    }

    record SpeedaResponse(Object message_id, String status, String remarks) {
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public void send(NotificationMessage message, Map<String, String> settings) {
        Credentials credentials = resolveCredentials(settings);
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

    /** Hub settings win key-by-key; env config fills the gaps; neither complete → a clear refusal. */
    private Credentials resolveCredentials(Map<String, String> settings) {
        String apiId = settings.getOrDefault("apiId", properties.apiId());
        String apiPassword = settings.getOrDefault("apiPassword", properties.apiPassword());
        String senderId = settings.getOrDefault("senderId", properties.senderId());
        if (apiId == null || apiId.isBlank() || apiPassword == null || apiPassword.isBlank()
                || senderId == null || senderId.isBlank()) {
            throw new IllegalStateException("No Speeda Mobile credentials: configure the SMS_PROVIDER "
                    + "integration (provider 'speedamobile') or set SPEEDA_API_ID/SPEEDA_API_PASSWORD/SPEEDA_SENDER_ID.");
        }
        return new Credentials(
                settings.getOrDefault("baseUrl", properties.baseUrl()),
                apiId, apiPassword, senderId,
                settings.getOrDefault("smsType", properties.smsType()),
                settings.getOrDefault("encoding", properties.encoding()));
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
