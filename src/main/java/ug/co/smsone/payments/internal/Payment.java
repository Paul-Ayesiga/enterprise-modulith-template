package ug.co.smsone.payments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One initiated collection. The gateway owns the outcome; this row converges to it. */
@Entity
@Table(name = "payment")
class Payment {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(name = "merchant_reference", nullable = false, length = 50)
    private String merchantReference;

    @Column(name = "gateway_reference", length = 100)
    private String gatewayReference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 100)
    private String description;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentStatus status;

    @Column(name = "status_detail", length = 255)
    private String statusDetail;

    @Column(name = "confirmation_code", length = 64)
    private String confirmationCode;

    @Column(name = "vat_amount", precision = 19, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "net_amount", precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "redirect_url", length = 1024)
    private String redirectUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Payment() {
        // JPA
    }

    static Payment initiate(UUID orgId, String provider, String mode, BigDecimal amount, String currency,
            String description, String phoneNumber, String email, Instant now) {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.orgId = orgId;
        payment.provider = provider;
        payment.mode = mode;
        // Pesapal caps the merchant reference at 50 chars; a UUID is 36 — unique per row by table constraint.
        payment.merchantReference = payment.id.toString();
        payment.amount = amount;
        payment.currency = currency;
        payment.description = description;
        payment.phoneNumber = phoneNumber;
        payment.email = email;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    void initiated(String gatewayReference, String redirectUrl, PaymentStatus status, String detail, Instant now) {
        this.gatewayReference = gatewayReference;
        this.redirectUrl = redirectUrl;
        applyStatus(status, detail, null, now);
    }

    /** Terminal states never regress — a late PENDING poll can't un-complete a payment. */
    void applyStatus(PaymentStatus next, String detail, String confirmationCode, Instant now) {
        if (this.status.terminal() && !this.status.equals(next)) {
            return;
        }
        this.status = next;
        this.statusDetail = detail;
        if (confirmationCode != null) {
            this.confirmationCode = confirmationCode;
        }
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getProvider() {
        return provider;
    }

    String getMode() {
        return mode;
    }

    String getMerchantReference() {
        return merchantReference;
    }

    String getGatewayReference() {
        return gatewayReference;
    }

    BigDecimal getAmount() {
        return amount;
    }

    String getCurrency() {
        return currency;
    }

    String getDescription() {
        return description;
    }

    String getPhoneNumber() {
        return phoneNumber;
    }

    String getEmail() {
        return email;
    }

    PaymentStatus getStatus() {
        return status;
    }

    String getStatusDetail() {
        return statusDetail;
    }

    String getConfirmationCode() {
        return confirmationCode;
    }

    void taxBreakdown(BigDecimal vatAmount, BigDecimal netAmount) {
        this.vatAmount = vatAmount;
        this.netAmount = netAmount;
    }

    BigDecimal getVatAmount() {
        return vatAmount;
    }

    BigDecimal getNetAmount() {
        return netAmount;
    }

    String getRedirectUrl() {
        return redirectUrl;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
