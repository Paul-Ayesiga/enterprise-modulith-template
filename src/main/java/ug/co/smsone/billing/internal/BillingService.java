package ug.co.smsone.billing.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.subscription.Subscriptions;

/**
 * Billing orchestration. Every Kill Bill call runs OUTSIDE the local transaction (§4 — the
 * TransactionTemplate opens only for the row write after the remote succeeded), and every state
 * that MATTERS flows one way: Kill Bill event → {@link #reconcile} → the subscription module's
 * audited assign path. Reconcile is idempotent by construction — it repeats what Kill Bill says.
 */
@Service
class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final BillingAccountRepository accounts;
    private final KillBillGateway killBill;
    private final KillBillProperties properties;
    private final Subscriptions subscriptions;
    private final TransactionTemplate transactionTemplate;
    private final AuditLog auditLog;
    private final org.springframework.beans.factory.ObjectProvider<BillingReceipts> receipts;

    BillingService(BillingAccountRepository accounts, KillBillGateway killBill,
            KillBillProperties properties, Subscriptions subscriptions,
            TransactionTemplate transactionTemplate, AuditLog auditLog,
            org.springframework.beans.factory.ObjectProvider<BillingReceipts> receipts) {
        this.accounts = accounts;
        this.killBill = killBill;
        this.properties = properties;
        this.subscriptions = subscriptions;
        this.transactionTemplate = transactionTemplate;
        this.auditLog = auditLog;
        this.receipts = receipts;
    }

    record BillingView(UUID orgId, UUID kbAccountId, java.math.BigDecimal balance, String currency,
            List<KillBillGateway.KbSubscription> subscriptions, List<String> paymentMethods) {
    }

    /**
     * Idempotent, and safe under concurrency: two provisions of the same org — e.g. the
     * auto-provision listener racing a manual admin call — both resolve Kill Bill's idempotent
     * account, then the unique {@code org_id} constraint elects one row. The loser resolves to that
     * row rather than surfacing the insert clash as a 500 (§4.4 idempotent-race, like the Keycloak
     * {@code createUser} 409). Remote first, row after.
     */
    BillingAccount provision(UUID orgId) {
        Optional<BillingAccount> existing = accounts.findByOrgId(orgId);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID kbAccountId = killBill.ensureAccount(orgId, "org-" + orgId);
        BillingAccount linked;
        try {
            linked = transactionTemplate.execute(tx ->
                    accounts.findByOrgId(orgId).orElseGet(() ->
                            accounts.save(BillingAccount.link(orgId, kbAccountId))));
        } catch (DataIntegrityViolationException raced) {
            // A concurrent provision won the insert; ensureAccount is idempotent by externalKey, so
            // both linked the same Kill Bill account — resolve to the committed row.
            return accounts.findByOrgId(orgId).orElseThrow(() -> raced);
        }
        auditLog.record("billing.account_provisioned", orgId, kbAccountId.toString(), null,
                "externalKey=" + orgId);
        return linked;
    }

    BillingView view(UUID orgId) {
        BillingAccount account = require(orgId);
        KillBillGateway.KbAccountView kb = killBill.accountView(account.getKbAccountId());
        return new BillingView(orgId, account.getKbAccountId(), kb.balance(), kb.currency(),
                killBill.subscriptions(account.getKbAccountId()),
                killBill.paymentMethods(account.getKbAccountId()));
    }

    List<KillBillGateway.KbInvoice> invoices(UUID orgId) {
        return killBill.invoices(require(orgId).getKbAccountId());
    }

    List<KillBillGateway.KbPaymentMethod> paymentMethods(UUID orgId) {
        return killBill.paymentMethodDetails(require(orgId).getKbAccountId());
    }

    /**
     * Attach a payment method via a KB payment plugin (raw card data never reaches us — see the
     * gateway). Audited. The org's own {@code /billing} surface stays reachable while a subscription
     * is paused, so this doubles as the pay-to-recover path out of read-only.
     */
    UUID addPaymentMethod(UUID orgId, String pluginName, boolean isDefault, Map<String, Object> pluginProperties) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new ValidationException("pluginName is required (the Kill Bill payment plugin).",
                    ApiSource.pointer("/data/attributes/pluginName"));
        }
        UUID paymentMethodId = killBill.addPaymentMethod(require(orgId).getKbAccountId(),
                pluginName.trim(), isDefault, pluginProperties);
        auditLog.record("billing.payment_method_added", orgId, paymentMethodId.toString(), null,
                "plugin=" + pluginName.trim() + (isDefault ? " default" : ""));
        return paymentMethodId;
    }

    void setDefaultPaymentMethod(UUID orgId, UUID paymentMethodId) {
        killBill.setDefaultPaymentMethod(require(orgId).getKbAccountId(), paymentMethodId);
        auditLog.record("billing.payment_method_default_set", orgId, paymentMethodId.toString(), null, null);
    }

    void removePaymentMethod(UUID orgId, UUID paymentMethodId) {
        killBill.deletePaymentMethod(require(orgId).getKbAccountId(), paymentMethodId);
        auditLog.record("billing.payment_method_removed", orgId, paymentMethodId.toString(), null, null);
    }

    /**
     * Billing-driven plan change: create the Kill Bill subscription, then reconcile immediately so
     * dev setups without the callback wired still see entitlements move; production converges the
     * same way when the push notification lands (reconcile is idempotent).
     */
    void subscribe(UUID orgId, String ourPlanCode) {
        String normalized = ourPlanCode == null ? "" : ourPlanCode.trim().toUpperCase();
        String kbPlan = properties.planMapping().get(normalized);
        if (kbPlan == null) {
            throw new ValidationException("plan must be one of the billable codes: "
                    + String.join(", ", properties.planMapping().keySet())
                    + " (FREE needs no billing subscription).",
                    ApiSource.pointer("/data/attributes/plan"));
        }
        BillingAccount account = provision(orgId);
        UUID kbSubscriptionId = killBill.createSubscription(account.getKbAccountId(), kbPlan);
        auditLog.record("billing.subscription_created", orgId, kbSubscriptionId.toString(), null,
                "plan=" + normalized + " kbPlan=" + kbPlan);
        reconcile(orgId);
    }

    /** Repeat what Kill Bill says into the entitlement authority — the ONE write direction. */
    void reconcile(UUID orgId) {
        BillingAccount account = require(orgId);
        List<KillBillGateway.KbSubscription> kbSubscriptions =
                killBill.subscriptions(account.getKbAccountId());
        String plan = kbSubscriptions.stream()
                .filter(sub -> "ACTIVE".equalsIgnoreCase(sub.state()))
                .map(sub -> properties.planCodeFor(sub.planName()))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("FREE"); // no billable subscription = the free tier, not limbo
        subscriptions.assignPlan(orgId, plan);
    }

    void onPaymentOutcome(UUID kbAccountId, boolean success) {
        accounts.findByKbAccountId(kbAccountId).ifPresentOrElse(account -> {
            subscriptions.markStatus(account.getOrgId(), success ? "ACTIVE" : "PAST_DUE");
            auditLog.record(success ? "billing.payment_succeeded" : "billing.payment_failed",
                    account.getOrgId(), kbAccountId.toString(), null, null);
            if (success) {
                BillingReceipts receiptSender = receipts.getIfAvailable();
                if (receiptSender != null) {
                    receiptSender.paymentReceived(account.getOrgId()); // best-effort, never throws
                }
            }
        }, () -> log.warn("Kill Bill payment event for unknown account {}", kbAccountId));
    }

    void onSubscriptionEvent(UUID kbAccountId) {
        accounts.findByKbAccountId(kbAccountId).ifPresentOrElse(
                account -> reconcile(account.getOrgId()),
                () -> log.warn("Kill Bill subscription event for unknown account {}", kbAccountId));
    }

    private BillingAccount require(UUID orgId) {
        return accounts.findByOrgId(orgId).orElseThrow(() ->
                new NotFoundException("The organization has no billing account yet — provision one first."));
    }
}
