package ug.co.smsone.payments.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The org's payment-collection surface: initiate a collection through a configured gateway and read
 * its converging status. Initiation is a money action → {@code org:update}; reading → {@code org:read}.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/payments")
@Tag(name = "Organization · Payments")
class PaymentController {

    static final String RESOURCE_TYPE = "payment";

    private final PaymentService service;

    PaymentController(PaymentService service) {
        this.service = service;
    }

    record InitiatePaymentRequest(String provider, @NotNull BigDecimal amount,
            @NotBlank String currency, @NotBlank String description, String phoneNumber, String email) {
    }

    record PaymentAttributes(String provider, String mode, String status, String statusDetail,
            BigDecimal amount, BigDecimal vatAmount, BigDecimal netAmount, String currency,
            String description, String merchantReference, String gatewayReference, String redirectUrl,
            String confirmationCode) {
    }

    @PostMapping
    @Operation(summary = "Initiate a payment collection",
            description = """
                    Starts a collection. `provider` is optional — omitted, the organization's \
                    configured PAYMENT_GATEWAY integration (org override, else platform default) \
                    names the gateway. Explicit values: `pesapal` (hosted \
                    page — send the payer to the returned `redirectUrl`; card + mobile money) or \
                    `yo-uganda` (a mobile-money approval prompt is pushed to `phoneNumber` — no \
                    redirect). Whether it runs against the sandbox or live money is the gateway's \
                    configured `mode`, echoed on the resource. The outcome converges from the \
                    gateway (IPN / on-read refresh) — poll the GET until the status is terminal.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject initiate(@PathVariable UUID orgId, @jakarta.validation.Valid @RequestBody InitiatePaymentRequest request) {
        Payment payment = service.initiate(orgId, request.provider(), request.amount(), request.currency(),
                request.description(), request.phoneNumber(), request.email());
        return toResource(payment);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read a payment (refreshes a PENDING one from its gateway)")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    ResourceObject get(@PathVariable UUID orgId, @PathVariable UUID id) {
        return toResource(service.get(orgId, id));
    }

    private static ResourceObject toResource(Payment payment) {
        return new ResourceObject(payment.getId().toString(), RESOURCE_TYPE, new PaymentAttributes(
                payment.getProvider(), payment.getMode(), payment.getStatus().name(),
                payment.getStatusDetail(), payment.getAmount(), payment.getVatAmount(),
                payment.getNetAmount(), payment.getCurrency(), payment.getDescription(),
                payment.getMerchantReference(), payment.getGatewayReference(),
                payment.getRedirectUrl(), payment.getConfirmationCode()));
    }
}
