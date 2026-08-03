package ug.co.smsone.notification.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static (env-driven) credentials for the Speeda Mobile SMS gateway — the fallback when the
 * integration hub has no {@code SMS_PROVIDER} configured. Per the Web SMS API v1.13: {@code smsType}
 * is {@code P} promotional / {@code T} transactional; {@code encoding} is {@code T} text / {@code U}
 * unicode (the sender auto-upgrades to {@code U} when the message needs it).
 */
@ConfigurationProperties(prefix = "app.notification.sms.speeda")
record SpeedaSmsProperties(
        String baseUrl,
        String apiId,
        String apiPassword,
        String senderId,
        String smsType,
        String encoding) {

    SpeedaSmsProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://apidocs.speedamobile.com";
        }
        if (smsType == null || smsType.isBlank()) {
            smsType = "T"; // platform notifications are transactional
        }
        if (encoding == null || encoding.isBlank()) {
            encoding = "T";
        }
    }

    boolean configured() {
        return apiId != null && !apiId.isBlank()
                && apiPassword != null && !apiPassword.isBlank()
                && senderId != null && !senderId.isBlank();
    }
}
