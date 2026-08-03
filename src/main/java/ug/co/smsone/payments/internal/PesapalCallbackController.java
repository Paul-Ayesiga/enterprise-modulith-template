package ug.co.smsone.payments.internal;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pesapal's server-to-server IPN and the browser's return callback. Neither carries a secret and
 * neither is trusted: both only TRIGGER a re-query of {@code GetTransactionStatus} by tracking id —
 * the gateway's own answer is the sole truth, so a forged ping can at worst make us re-confirm.
 * The IPN answers the exact acknowledgment shape Pesapal expects ({@code status: 200}). Permitted
 * without authentication in the security config (like the Kill Bill notification endpoint).
 */
@Hidden
@RestController
@RequestMapping("/api/v1/payments/pesapal")
class PesapalCallbackController {

    private final PaymentService service;

    PesapalCallbackController(PaymentService service) {
        this.service = service;
    }

    /** Pesapal registered-IPN ping (GET registration; POST tolerated for completeness). */
    @GetMapping("/ipn")
    Map<String, Object> ipnGet(@RequestParam("OrderTrackingId") String orderTrackingId,
            @RequestParam(value = "OrderMerchantReference", required = false) String merchantReference,
            @RequestParam(value = "OrderNotificationType", required = false) String notificationType) {
        return acknowledge(orderTrackingId, merchantReference, notificationType);
    }

    @PostMapping("/ipn")
    Map<String, Object> ipnPost(@RequestParam("OrderTrackingId") String orderTrackingId,
            @RequestParam(value = "OrderMerchantReference", required = false) String merchantReference,
            @RequestParam(value = "OrderNotificationType", required = false) String notificationType) {
        return acknowledge(orderTrackingId, merchantReference, notificationType);
    }

    /** The browser's return leg from the hosted page — refresh, then a tiny closable answer. */
    @GetMapping("/callback")
    Map<String, Object> callback(@RequestParam("OrderTrackingId") String orderTrackingId,
            @RequestParam(value = "OrderMerchantReference", required = false) String merchantReference) {
        service.refreshByGatewayReference(orderTrackingId);
        return Map.of("received", true, "orderTrackingId", orderTrackingId,
                "note", "Payment status is confirmed with Pesapal server-side — you can close this page.");
    }

    private Map<String, Object> acknowledge(String orderTrackingId, String merchantReference,
            String notificationType) {
        int status;
        try {
            service.refreshByGatewayReference(orderTrackingId);
            status = 200;
        } catch (RuntimeException e) {
            status = 500; // Pesapal retries on a 500 acknowledgment
        }
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("orderNotificationType", notificationType == null ? "IPNCHANGE" : notificationType);
        ack.put("orderTrackingId", orderTrackingId);
        ack.put("orderMerchantReference", merchantReference);
        ack.put("status", status);
        return ack;
    }
}
