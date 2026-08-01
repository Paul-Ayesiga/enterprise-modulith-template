package ug.co.smsone.billing.internal;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kill Bill's push-notification sink — a MACHINE endpoint ({@code @Hidden}: it belongs in no
 * client spec or Postman folder). Kill Bill cannot do OAuth, so the path is permit-listed in
 * {@code SecurityConfig} and authenticated here by the shared token in the registered callback URL
 * (constant-time compare). Anything transient throws → 5xx → Kill Bill retries; unknown event
 * types are 200-acknowledged and ignored, so a Kill Bill upgrade never builds a retry storm.
 */
@io.swagger.v3.oas.annotations.Hidden
@RestController
@RequestMapping("/api/v1/billing")
class KillBillNotificationController {

    private static final Logger log = LoggerFactory.getLogger(KillBillNotificationController.class);

    private final BillingService billing;
    private final KillBillProperties properties;

    KillBillNotificationController(BillingService billing, KillBillProperties properties) {
        this.billing = billing;
        this.properties = properties;
    }

    @PostMapping("/killbill/notifications")
    ResponseEntity<Void> onNotification(@RequestParam(name = "token", required = false) String token,
            @RequestBody Map<String, Object> notification) {
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                properties.callbackToken().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        String eventType = String.valueOf(notification.get("eventType"));
        Object accountId = notification.get("accountId");
        if (accountId == null) {
            return ResponseEntity.ok().build(); // tenant-level events carry no account; nothing to do
        }
        UUID kbAccountId = UUID.fromString(String.valueOf(accountId));
        switch (eventType) {
            case "SUBSCRIPTION_CREATION", "SUBSCRIPTION_CHANGE", "SUBSCRIPTION_CANCEL",
                 "SUBSCRIPTION_PHASE", "SUBSCRIPTION_UNCANCEL"
                    -> billing.onSubscriptionEvent(kbAccountId);
            case "INVOICE_PAYMENT_SUCCESS" -> billing.onPaymentOutcome(kbAccountId, true);
            case "INVOICE_PAYMENT_FAILED" -> billing.onPaymentOutcome(kbAccountId, false);
            default -> log.debug("Ignoring Kill Bill event {} for account {}", eventType, kbAccountId);
        }
        return ResponseEntity.ok().build();
    }
}
