package ug.co.smsone.payments.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ug.co.smsone.integration.Integrations;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Payment orchestration. The remote call runs OUTSIDE the row transaction (§4: remote first, row
 * after), and outcomes converge from the gateway's own word — the Pesapal IPN and the browser
 * callback only trigger a re-query, and an on-read refresh covers Yo!'s poll-based flow. Terminal
 * transitions are audited once (the entity refuses to regress, so a duplicate IPN is a no-op).
 */
@Service
class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final Map<String, PaymentGateway> gateways;
    private final ObjectProvider<Integrations> integrations;
    private final AuditLog auditLog;
    private final Clock clock;

    PaymentService(PaymentRepository payments, List<PaymentGateway> gateways,
            ObjectProvider<Integrations> integrations, AuditLog auditLog, Clock clock) {
        this.payments = payments;
        this.gateways = gateways.stream()
                .collect(Collectors.toUnmodifiableMap(PaymentGateway::provider, Function.identity()));
        this.integrations = integrations;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    Payment initiate(UUID orgId, String provider, BigDecimal amount, String currency, String description,
            String phoneNumber, String email) {
        PaymentGateway gateway = requireGateway(orgId, provider);
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("amount must be positive.", ApiSource.pointer("/data/attributes/amount"));
        }
        if (currency == null || !currency.matches("[A-Za-z]{3}")) {
            throw new ValidationException("currency must be a 3-letter ISO code (e.g. UGX).",
                    ApiSource.pointer("/data/attributes/currency"));
        }
        if (description == null || description.isBlank() || description.length() > 100) {
            throw new ValidationException("description is required (max 100 characters).",
                    ApiSource.pointer("/data/attributes/description"));
        }
        if ((phoneNumber == null || phoneNumber.isBlank()) && (email == null || email.isBlank())) {
            throw new ValidationException("phoneNumber or email is required (the payer's contact).",
                    ApiSource.pointer("/data/attributes/phoneNumber"));
        }
        Payment payment = Payment.initiate(orgId, gateway.provider(), gateway.mode(orgId), amount,
                currency.toUpperCase(), description.trim(), blankToNull(phoneNumber), blankToNull(email),
                clock.instant());
        // Remote first: only a gateway-accepted collection gets a row.
        PaymentGateway.Initiation initiation = gateway.initiate(orgId, payment);
        payment.initiated(initiation.gatewayReference(), initiation.redirectUrl(), initiation.status(),
                initiation.detail(), clock.instant());
        Payment saved = payments.save(payment);
        auditLog.record("payment.initiated", orgId, saved.getId().toString(), null,
                "provider=" + saved.getProvider() + " mode=" + saved.getMode()
                        + " amount=" + saved.getAmount() + " " + saved.getCurrency());
        return saved;
    }

    /** Read-with-refresh: a PENDING row asks its gateway before answering; terminal rows are settled. */
    @Transactional
    Payment get(UUID orgId, UUID id) {
        Payment payment = payments.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("No such payment."));
        return refresh(payment);
    }

    /** The Pesapal IPN/callback trigger: look up by tracking id and re-query — never trust the caller. */
    @Transactional
    void refreshByGatewayReference(String gatewayReference) {
        payments.findByGatewayReference(gatewayReference).ifPresentOrElse(this::refresh,
                () -> log.warn("Payment notification for unknown gateway reference {}", gatewayReference));
    }

    private Payment refresh(Payment payment) {
        if (payment.getStatus().terminal() || payment.getGatewayReference() == null) {
            return payment;
        }
        PaymentStatus before = payment.getStatus();
        PaymentGateway.StatusResult result = requireGateway(payment.getOrgId(), payment.getProvider())
                .status(payment.getOrgId(), payment);
        payment.applyStatus(result.status(), result.detail(), result.confirmationCode(), clock.instant());
        Payment saved = payments.save(payment);
        if (saved.getStatus() != before && saved.getStatus().terminal()) {
            auditLog.record("payment." + saved.getStatus().name().toLowerCase(), saved.getOrgId(),
                    saved.getId().toString(), "status=" + before, "status=" + saved.getStatus()
                            + (saved.getConfirmationCode() == null ? "" : " confirmation=" + saved.getConfirmationCode()));
        }
        return saved;
    }

    /**
     * An omitted provider means "the org's configured choice": the integration hub's
     * PAYMENT_GATEWAY entry (org override, else platform default) names the gateway — so which PSP
     * serves an organization is database configuration, not caller knowledge.
     */
    private PaymentGateway requireGateway(UUID orgId, String provider) {
        String effective = provider == null || provider.isBlank() ? configuredProvider(orgId) : provider;
        PaymentGateway gateway = effective == null ? null : gateways.get(effective.trim().toLowerCase());
        if (gateway == null) {
            throw new ValidationException((effective == null
                    ? "provider is required (this organization has no configured payment gateway); one of: "
                    : "provider must be one of: ") + String.join(", ", gateways.keySet()),
                    ApiSource.pointer("/data/attributes/provider"));
        }
        return gateway;
    }

    private String configuredProvider(UUID orgId) {
        Integrations hub = integrations.getIfAvailable();
        return hub == null ? null : hub.resolve(orgId, Integrations.Kind.PAYMENT_GATEWAY)
                .map(Integrations.ResolvedIntegration::provider).orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
