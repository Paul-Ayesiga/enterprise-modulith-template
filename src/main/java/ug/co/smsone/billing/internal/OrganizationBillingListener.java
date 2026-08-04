package ug.co.smsone.billing.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.OrganizationRegistered;

/**
 * Bridges a new organization into Kill Bill: when one is registered, provision its billing account
 * under the platform's single Kill Bill tenant — so "create an org" yields a Kill Bill account with
 * no separate admin call. Opt-in ({@code app.billing.auto-provision-accounts}) because it presumes a
 * reachable, bootstrapped tenant; off by default so environments without Kill Bill do not accrue
 * retrying events.
 *
 * <p>{@link ApplicationModuleListener} runs after the organization's transaction commits,
 * asynchronously in its own transaction and recorded in the event publication registry — so a Kill
 * Bill outage defers the provisioning (it is retried) instead of losing it or rolling the org back.
 * {@link BillingService#provision} is idempotent, so a retry — or a later manual provision — is safe.
 */
@Component
@ConditionalOnProperty(name = "app.billing.auto-provision-accounts", havingValue = "true")
class OrganizationBillingListener {

    private static final Logger log = LoggerFactory.getLogger(OrganizationBillingListener.class);

    private final BillingService billing;

    OrganizationBillingListener(BillingService billing) {
        this.billing = billing;
    }

    @ApplicationModuleListener
    void on(OrganizationRegistered event) {
        billing.provision(event.orgId());
        log.info("Auto-provisioned a Kill Bill account for organization {} ({})", event.alias(), event.orgId());
    }
}
