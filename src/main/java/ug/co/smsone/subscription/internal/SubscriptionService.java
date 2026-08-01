package ug.co.smsone.subscription.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.subscription.SubscriptionChanged;

/**
 * Assigning plans (platform act, audited) and reading an org's effective commercial state. Also
 * implements the {@link ug.co.smsone.subscription.Subscriptions} write port — the billing
 * integration drives plan state through the SAME audited paths the admin surface uses.
 */
@Service
class SubscriptionService implements ug.co.smsone.subscription.Subscriptions {

    private final OrgSubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final EntitlementResolver resolver;
    private final ApplicationEventPublisher events;
    private final AuditLog auditLog;

    SubscriptionService(OrgSubscriptionRepository subscriptions, PlanRepository plans,
            EntitlementResolver resolver, ApplicationEventPublisher events, AuditLog auditLog) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.resolver = resolver;
        this.events = events;
        this.auditLog = auditLog;
    }

    record SubscriptionView(UUID orgId, String planCode, String planName, String status,
            Instant currentPeriodEnd, Map<String, Long> entitlements) {
    }

    @Transactional(readOnly = true)
    SubscriptionView view(UUID orgId) {
        Plan plan = resolver.planOf(orgId)
                .orElseThrow(() -> new NotFoundException("No plan catalog is seeded."));
        OrgSubscription subscription = subscriptions.findByOrgId(orgId).orElse(null);
        // On the wire, feature-on is a null VALUE (the documented encoding); the storage sentinel
        // is an implementation detail that must not leak.
        Map<String, Long> entitlements = new LinkedHashMap<>();
        plan.getEntitlements().forEach((key, value) ->
                entitlements.put(key, value == null || value < 0 ? null : value));
        return new SubscriptionView(orgId, plan.getCode(), plan.getName(),
                subscription == null ? OrgSubscription.Status.ACTIVE.name()
                        : subscription.getStatus().name(),
                subscription == null ? null : subscription.getCurrentPeriodEnd(), entitlements);
    }

    /** The port's void form — billing drives the SAME audited path the admin surface returns from. */
    @Override
    @Transactional
    public void assignPlan(UUID organizationId, String planCode) {
        assign(organizationId, planCode);
    }

    @Transactional
    SubscriptionView assign(UUID orgId, String planCode) {
        String normalized = planCode == null ? "" : planCode.trim().toUpperCase();
        Plan plan = plans.findByCode(normalized)
                .orElseThrow(() -> new NotFoundException("No plan named '" + normalized + "'."));
        String previous = resolver.planOf(orgId).map(Plan::getCode).orElse(null);
        OrgSubscription subscription = subscriptions.findByOrgId(orgId)
                .map(existing -> {
                    existing.changePlan(plan.getId());
                    return existing;
                })
                .orElseGet(() -> OrgSubscription.of(orgId, plan.getId()));
        subscriptions.save(subscription);
        // Explicit publication: an update fires no @DomainEvents, and the evictor must run so a
        // DOWNGRADE bites the very next gate check, not the cache TTL.
        events.publishEvent(new SubscriptionChanged(orgId, plan.getCode(),
                subscription.getStatus().name(), Instant.now()));
        auditLog.record("subscription.plan_assigned", orgId, orgId.toString(),
                previous == null ? null : "plan=" + previous, "plan=" + plan.getCode());
        return view(orgId);
    }

    java.util.List<Plan> catalog() {
        return plans.findAllByOrderByRankAsc();
    }

    @Override
    @Transactional
    public void markStatus(UUID organizationId, String status) {
        OrgSubscription subscription = subscriptions.findByOrgId(organizationId).orElse(null);
        if (subscription == null) {
            // Billing events can race provisioning; standing without a subscription is meaningless.
            return;
        }
        OrgSubscription.Status parsed;
        try {
            parsed = OrgSubscription.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return; // an unknown standing from an integration is logged upstream, never a 500 here
        }
        if (subscription.getStatus() == parsed) {
            return;
        }
        String before = subscription.getStatus().name();
        subscription.markStatus(parsed);
        subscriptions.save(subscription);
        Plan plan = plans.findById(subscription.getPlanId()).orElse(null);
        events.publishEvent(new SubscriptionChanged(organizationId,
                plan == null ? null : plan.getCode(), parsed.name(), Instant.now()));
        auditLog.record("subscription.status_changed", organizationId, organizationId.toString(),
                "status=" + before, "status=" + parsed.name());
    }
}
