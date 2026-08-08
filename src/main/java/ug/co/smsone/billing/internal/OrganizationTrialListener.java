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
import ug.co.smsone.subscription.Subscriptions;

/**
 * Trial-on-signup: when an org is registered, start a default paid-plan trial so a new tenant lands
 * on {@code TRIALING} (full access) rather than silently on FREE. Opt-in
 * ({@code app.billing.trial-on-signup.enabled}); plan and length are configurable.
 *
 * <p>It lives in billing — the commercial-integration module that already reacts to org creation (the
 * Kill Bill account) and drives the {@link Subscriptions} port — because {@code subscription} is a
 * lower-level module (others gate on its {@code Entitlements}) and must not depend back on
 * {@code organization}. The port's {@code startTrialForNewOrg} is a no-op when the org already has a
 * subscription, so a straight-to-paid assignment made at (or right after) creation wins. The trial is
 * entitlement-only — no Kill Bill subscription or invoice until it converts — so it is independent of
 * the account auto-provisioned off the same event.
 *
 * <p>After the org tx commits, async, recorded in the event publication registry — a transient
 * failure is retried, not lost. Spelled as {@code @Async} + {@code @TransactionalEventListener}
 * rather than {@link ApplicationModuleListener} for the reason on {@link #on}.
 */
@Component
@ConditionalOnProperty(name = "app.billing.trial-on-signup.enabled", havingValue = "true")
class OrganizationTrialListener {

    private static final Logger log = LoggerFactory.getLogger(OrganizationTrialListener.class);

    private final Subscriptions subscriptions;
    private final TrialOnSignupProperties properties;

    OrganizationTrialListener(Subscriptions subscriptions, TrialOnSignupProperties properties) {
        this.subscriptions = subscriptions;
        this.properties = properties;
    }

    /**
     * The organization the event names is the axis, declared before any connection is borrowed.
     * {@code org_subscription} — the row {@code startTrialForNewOrg} writes — is tenant-tier (ADR 0010
     * §2), and a pooled listener thread carries no axis at all, so without this the trial lands in the
     * empty {@code no_tenant} schema. {@link ApplicationModuleListener} would bundle
     * {@code @Transactional} and open that connection before the method body could pin anything, which
     * is why the annotation is split apart here — the same split as
     * {@code DeviceTrustRevocationListener}. The port opens its own transaction inside the pin.
     */
    @Async
    @TransactionalEventListener
    void on(OrganizationRegistered event) {
        TenantContext.runAs(event.orgId(),
                () -> subscriptions.startTrialForNewOrg(event.orgId(), properties.plan(), properties.days()));
        log.info("Applied trial-on-signup ({} {}-day) to new organization {} ({})",
                properties.plan(), properties.days(), event.alias(), event.orgId());
    }
}
