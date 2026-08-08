package ug.co.smsone.billing.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Bridges a new organization into Kill Bill: when one is registered, provision its billing account
 * under the platform's single Kill Bill tenant — so "create an org" yields a Kill Bill account with
 * no separate admin call. Opt-in ({@code app.billing.auto-provision-accounts}) because it presumes a
 * reachable, bootstrapped tenant; off by default so environments without Kill Bill do not accrue
 * retrying events.
 *
 * <p>It runs after the organization's transaction commits, asynchronously, and is recorded in the
 * event publication registry — so a Kill Bill outage defers the provisioning (it is retried) instead
 * of losing it or rolling the org back. {@link BillingService#provision} is idempotent, so a retry —
 * or a later manual provision — is safe. It is {@code @Async} + {@code @TransactionalEventListener}
 * rather than {@link ApplicationModuleListener} because the tenant axis has to be declared before a
 * connection is borrowed; see {@link #on}.
 */
@Component
@ConditionalOnProperty(name = "app.billing.auto-provision-accounts", havingValue = "true")
class OrganizationBillingListener {

    private static final Logger log = LoggerFactory.getLogger(OrganizationBillingListener.class);

    private final BillingService billing;

    OrganizationBillingListener(BillingService billing) {
        this.billing = billing;
    }

    /**
     * The event carries the axis, and the axis is declared OUTSIDE the transaction — which is why this
     * is spelled out as {@code @Async} + {@code @TransactionalEventListener} rather than
     * {@link ApplicationModuleListener}. That annotation bundles {@code @Transactional} as well, so the
     * method would be entered with a connection already borrowed on a pooled thread that carries no
     * axis, and {@code TenantContext} refuses to pin there (the schema is chosen at borrow, so the pin
     * would be a silent no-op). {@code billing_account} is tenant-tier (ADR 0010 §2), so on that
     * connection the write lands nowhere: {@code relation "billing_account" does not exist},
     * asynchronously, retried forever by the outbox. Same split as
     * {@code DeviceTrustRevocationListener}; the publication registry still sees the listener and still
     * retries an incomplete one.
     *
     * <p>No {@code TransactionTemplate} around the call either, and that is deliberate rather than an
     * omission: {@link BillingService#provision} opens its own transaction for the row write AFTER the
     * Kill Bill round trip has succeeded, precisely so a remote call never runs inside a local
     * transaction. Wrapping one around it here would put it back.
     */
    @Async
    @TransactionalEventListener
    void on(OrganizationRegistered event) {
        TenantContext.runAs(event.orgId(), () -> billing.provision(event.orgId()));
        log.info("Auto-provisioned a Kill Bill account for organization {} ({})", event.alias(), event.orgId());
    }
}
