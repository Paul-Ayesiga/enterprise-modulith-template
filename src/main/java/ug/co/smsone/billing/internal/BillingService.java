package ug.co.smsone.billing.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    BillingService(BillingAccountRepository accounts, KillBillGateway killBill,
            KillBillProperties properties, Subscriptions subscriptions,
            TransactionTemplate transactionTemplate, AuditLog auditLog) {
        this.accounts = accounts;
        this.killBill = killBill;
        this.properties = properties;
        this.subscriptions = subscriptions;
        this.transactionTemplate = transactionTemplate;
        this.auditLog = auditLog;
    }

    record BillingView(UUID orgId, UUID kbAccountId, java.math.BigDecimal balance, String currency,
            List<KillBillGateway.KbSubscription> subscriptions, List<String> paymentMethods) {
    }

    /** Idempotent: provisioning twice returns the same linkage. Remote first, row after. */
    BillingAccount provision(UUID orgId) {
        Optional<BillingAccount> existing = accounts.findByOrgId(orgId);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID kbAccountId = killBill.ensureAccount(orgId, "org-" + orgId);
        BillingAccount linked = transactionTemplate.execute(tx ->
                accounts.findByOrgId(orgId).orElseGet(() ->
                        accounts.save(BillingAccount.link(orgId, kbAccountId))));
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
