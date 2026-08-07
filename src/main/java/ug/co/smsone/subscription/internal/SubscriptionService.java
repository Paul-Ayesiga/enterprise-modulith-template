package ug.co.smsone.subscription.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.subscription.EntitlementKeys;
import ug.co.smsone.subscription.SubscriptionChanged;

/**
 * Assigning plans (platform act, audited) and reading an org's effective commercial state. Also
 * implements the {@link ug.co.smsone.subscription.Subscriptions} write port — the billing
 * integration drives plan state through the SAME audited paths the admin surface uses.
 */
@Service
class SubscriptionService implements ug.co.smsone.subscription.Subscriptions {

    /** Default trial length when the caller does not specify one. */
    static final int DEFAULT_TRIAL_DAYS = 14;

    private final OrgSubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final EntitlementResolver resolver;
    private final ApplicationEventPublisher events;
    private final AuditLog auditLog;
    private final MeterRegistry meters;
    private final Clock clock;

    SubscriptionService(OrgSubscriptionRepository subscriptions, PlanRepository plans,
            EntitlementResolver resolver, ApplicationEventPublisher events, AuditLog auditLog,
            MeterRegistry meters, Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.resolver = resolver;
        this.events = events;
        this.auditLog = auditLog;
        this.meters = meters;
        this.clock = clock;
    }

    record SubscriptionView(UUID orgId, String planCode, String planName, String status,
            Instant currentPeriodEnd, Instant trialEndsAt, Map<String, Long> entitlements) {
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
                subscription == null ? null : subscription.getCurrentPeriodEnd(),
                subscription == null ? null : subscription.getTrialEndsAt(), entitlements);
    }

    /** The port's void form — billing drives the SAME audited path the admin surface returns from. */
    @Override
    @Transactional
    public void assignPlan(UUID organizationId, String planCode) {
        assign(organizationId, planCode);
    }

    /** Port form — billing (e.g. a Kill Bill trial phase) can drive a trial through the audited path. */
    @Override
    @Transactional
    public void startTrial(UUID organizationId, String planCode, int trialDays) {
        beginTrial(organizationId, planCode, trialDays);
    }

    /** Port form — the trial-on-signup listener drives this; an org already on a plan is left alone. */
    @Override
    @Transactional
    public void startTrialForNewOrg(UUID organizationId, String planCode, int trialDays) {
        if (subscriptions.findByOrgId(organizationId).isPresent()) {
            return; // already on a plan (e.g. a straight-to-paid assignment) — do not override it
        }
        beginTrial(organizationId, planCode, trialDays);
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

    /**
     * Start (or restart) a paid-plan trial: the org runs on {@code planCode} as {@code TRIALING}
     * for {@code trialDays} (default {@value #DEFAULT_TRIAL_DAYS}) with full access, then the
     * expiry job pauses it. FREE has nothing to trial and is refused (422).
     */
    @Transactional
    SubscriptionView beginTrial(UUID orgId, String planCode, int trialDays) {
        String normalized = planCode == null ? "" : planCode.trim().toUpperCase();
        Plan plan = plans.findByCode(normalized)
                .orElseThrow(() -> new NotFoundException("No plan named '" + normalized + "'."));
        if ("FREE".equals(plan.getCode())) {
            throw new ValidationException("The FREE plan has nothing to trial — trials are for paid plans.");
        }
        int days = trialDays <= 0 ? DEFAULT_TRIAL_DAYS : trialDays;
        Instant endsAt = clock.instant().plus(Duration.ofDays(days));
        String previous = resolver.planOf(orgId).map(Plan::getCode).orElse(null);
        OrgSubscription subscription = subscriptions.findByOrgId(orgId)
                .map(existing -> {
                    existing.startTrial(plan.getId(), endsAt);
                    return existing;
                })
                .orElseGet(() -> OrgSubscription.trial(orgId, plan.getId(), endsAt));
        subscriptions.save(subscription);
        events.publishEvent(new SubscriptionChanged(orgId, plan.getCode(),
                OrgSubscription.Status.TRIALING.name(), clock.instant()));
        auditLog.record("subscription.trial_started", orgId, orgId.toString(),
                previous == null ? null : "plan=" + previous,
                "plan=" + plan.getCode() + " trialEndsAt=" + endsAt);
        return view(orgId);
    }

    /** Port form — billing's dunning job. PAST_DUE older than the grace window goes read-only. */
    @Override
    @Transactional
    public int pauseLapsedPastDue(Duration grace) {
        List<OrgSubscription> lapsed = subscriptions.findByStatusAndUpdatedAtBefore(
                OrgSubscription.Status.PAST_DUE, clock.instant().minus(grace));
        Map<UUID, String> planCodes = planCodesOf(lapsed);
        for (OrgSubscription subscription : lapsed) {
            subscription.pause();
            subscriptions.save(subscription);
            events.publishEvent(new SubscriptionChanged(subscription.getOrgId(),
                    planCodes.get(subscription.getPlanId()),
                    OrgSubscription.Status.PAUSED.name(), clock.instant()));
            auditLog.record("subscription.past_due_lapsed", subscription.getOrgId(),
                    subscription.getOrgId().toString(), "status=PAST_DUE", "status=PAUSED");
            meters.counter("smsone.subscription.past_due_lapsed").increment();
        }
        return lapsed.size();
    }

    /**
     * Pause every trial that has lapsed — the org goes READ-ONLY (writes answer 402) until a plan
     * is assigned or a payment lands. Idempotent: a paused row is no longer TRIALING, so a re-run
     * skips it. Returns how many were paused. Driven by {@link TrialExpiryJob}.
     */
    @Transactional
    public int expireTrials() {
        List<OrgSubscription> lapsed = subscriptions
                .findByStatusAndTrialEndsAtBefore(OrgSubscription.Status.TRIALING, clock.instant());
        Map<UUID, String> planCodes = planCodesOf(lapsed);
        for (OrgSubscription subscription : lapsed) {
            subscription.pause();
            subscriptions.save(subscription);
            events.publishEvent(new SubscriptionChanged(subscription.getOrgId(),
                    planCodes.get(subscription.getPlanId()),
                    OrgSubscription.Status.PAUSED.name(), clock.instant()));
            auditLog.record("subscription.trial_expired", subscription.getOrgId(),
                    subscription.getOrgId().toString(), "status=TRIALING", "status=PAUSED");
            meters.counter("smsone.subscription.trial_expired").increment();
        }
        return lapsed.size();
    }

    /**
     * The plan CODE behind each lapsed row, in one query. A {@code findById} inside the sweep loop was
     * only ever de-duplicated by the shared persistence context; this states the intent instead of
     * relying on it, and an unknown plan id is simply absent from the map — the same null the event
     * carried before.
     */
    private Map<UUID, String> planCodesOf(List<OrgSubscription> lapsed) {
        Set<UUID> planIds = lapsed.stream()
                .map(OrgSubscription::getPlanId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (planIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> codes = new java.util.HashMap<>();
        plans.findAllById(planIds).forEach(plan -> codes.put(plan.getId(), plan.getCode()));
        return codes;
    }

    java.util.List<Plan> catalog() {
        return plans.findAllByOrderByRankAsc();
    }

    private static final Set<String> KNOWN_ENTITLEMENTS = Set.of(
            EntitlementKeys.MEMBERS_MAX, EntitlementKeys.WEBHOOKS_MAX, EntitlementKeys.EXCHANGE_ENABLED,
            EntitlementKeys.EXCHANGE_SCHEDULES_MAX, EntitlementKeys.API_REQUESTS_PER_MINUTE);

    @Transactional
    Plan createPlan(String code, String name, int rank, Map<String, Long> entitlements) {
        String normalized = normalizeCode(code);
        if (plans.findByCode(normalized).isPresent()) {
            throw new ConflictException("A plan named '" + normalized + "' already exists.");
        }
        Plan plan = plans.save(Plan.of(normalized, name.trim(), rank, toStored(entitlements)));
        auditLog.record("subscription.plan_created", null, normalized, null,
                "name=" + plan.getName() + " rank=" + rank);
        return plan;
    }

    /** Editing a plan changes what EVERY org on it may do, so evict the per-org entitlement cache. */
    @Transactional
    @CacheEvict(cacheNames = EntitlementResolver.CACHE, allEntries = true)
    Plan updatePlan(String code, String name, int rank, Map<String, Long> entitlements) {
        Plan plan = requirePlan(normalizeCode(code));
        plan.update(name.trim(), rank, toStored(entitlements));
        plans.save(plan);
        auditLog.record("subscription.plan_updated", null, plan.getCode(), null,
                "name=" + plan.getName() + " rank=" + rank);
        return plan;
    }

    @Transactional
    @CacheEvict(cacheNames = EntitlementResolver.CACHE, allEntries = true)
    void deletePlan(String code) {
        Plan plan = requirePlan(normalizeCode(code));
        if ("FREE".equals(plan.getCode())) {
            throw new ConflictException("FREE is the default fallback plan and cannot be deleted.");
        }
        if (subscriptions.existsByPlanId(plan.getId())) {
            throw new ConflictException("This plan is assigned to organizations — reassign them before deleting it.");
        }
        plans.delete(plan);
        auditLog.record("subscription.plan_deleted", null, plan.getCode(), "name=" + plan.getName(), null);
    }

    private Plan requirePlan(String code) {
        return plans.findByCode(code).orElseThrow(() -> new NotFoundException("No plan named '" + code + "'."));
    }

    private static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (normalized.isBlank()) {
            throw new ValidationException("A plan code is required.", ApiSource.pointer("/code"));
        }
        return normalized;
    }

    /**
     * Validate the entitlement map and encode it for STORAGE: a null value means "feature on" (kept as
     * the negative sentinel so Hibernate doesn't drop it), a number is a cap, an unknown key is refused
     * (a typo would otherwise silently do nothing), and an absent key stays off / unlimited.
     */
    private static Map<String, Long> toStored(Map<String, Long> entitlements) {
        Map<String, Long> stored = new LinkedHashMap<>();
        if (entitlements == null) {
            return stored;
        }
        entitlements.forEach((key, value) -> {
            if (!KNOWN_ENTITLEMENTS.contains(key)) {
                throw new ValidationException("Unknown entitlement '" + key + "'.",
                        ApiSource.pointer("/entitlements/" + key));
            }
            if (value == null) {
                stored.put(key, PlanSeeder.FEATURE_ON);
            } else if (value < 0) {
                throw new ValidationException(
                        "An entitlement cap cannot be negative — omit the key for off, or send null for on.",
                        ApiSource.pointer("/entitlements/" + key));
            } else {
                stored.put(key, value);
            }
        });
        return stored;
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
