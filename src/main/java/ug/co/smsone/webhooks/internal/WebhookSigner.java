package ug.co.smsone.webhooks.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 signature of a payload with the subscription secret — receivers verify integrity/origin. */
final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign webhook payload", ex);
        }
    }
}
