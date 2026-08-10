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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.cache.TenantCacheKeys;
import ug.co.smsone.shared.cache.TwoLevelCacheManager;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.subscription.EntitlementKeys;
import ug.co.smsone.subscription.PlanCatalog;
import ug.co.smsone.subscription.PlanSnapshot;
import ug.co.smsone.subscription.SubscriptionChanged;

/**
 * Assigning plans (platform act, audited) and reading an org's effective commercial state. Also
 * implements the {@link ug.co.smsone.subscription.Subscriptions} write port — the billing
 * integration drives plan state through the SAME audited paths the admin surface uses.
 *
 * <p><b>Two ways to reach a plan, split on purpose (ADR 0011 §6).</b> READS that cross the tier
 * boundary — an {@code org_subscription} row's plan, a lapsed trial's code for its event — go through
 * {@link PlanCatalog} and inherit its cache and staleness contract. WRITE paths ({@link #assign},
 * {@link #beginTrial}) and the catalog's own administration keep {@link PlanRepository}: a plan
 * assignment must be built on an authoritative read, never on a snapshot that may be 60 seconds
 * behind an operator's edit — a subscription created against a just-deleted plan would be exactly the
 * dangling soft ref {@code PlanCatalogGuard} exists to catch.
 */
@Service
class SubscriptionService implements ug.co.smsone.subscription.Subscriptions {

    /** Default trial length when the caller does not specify one. */
    static final int DEFAULT_TRIAL_DAYS = 14;

    /** The plan every organization is on when it has no {@code org_subscription} row at all. */
    private static final String FREE_PLAN = "FREE";

    /**
     * The axis the "who is on this plan" question borrows, when it names no organization.
     *
     * <p>Same constant and same reasoning as {@code AdminSubscriptionController} and
     * {@code MappedSchemaValidator}: an org that has never been promoted resolves to the shared
     * {@code tenant_pool}, and a UUID in no {@code organization} row can never resolve to anything else
     * — so this IS the pooled schema's axis, spelled with the only vocabulary {@code TenantContext} has.
     * One idiom for "the pool", not several.
     *
     * <p>When silos exist (ADR 0010 Phase 5) the affected organizations stop living in one schema and
     * this becomes a loop over {@code platform.tenant_placement}, accumulating the ids from each home.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

    private final OrgSubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final PlanCatalog catalog;
    private final PlanCatalogCache planCatalogCache;
    private final EntitlementResolver resolver;
    private final ApplicationEventPublisher events;
    private final AuditLog auditLog;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TwoLevelCacheManager caches;
    private final TransactionTemplate transactions;

    SubscriptionService(OrgSubscriptionRepository subscriptions, PlanRepository plans,
            PlanCatalog catalog, PlanCatalogCache planCatalogCache, EntitlementResolver resolver,
            ApplicationEventPublisher events, AuditLog auditLog, MeterRegistry meters, Clock clock,
            TwoLevelCacheManager caches, TransactionTemplate transactions) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.catalog = catalog;
        this.planCatalogCache = planCatalogCache;
        this.resolver = resolver;
        this.events = events;
        this.auditLog = auditLog;
        this.meters = meters;
        this.clock = clock;
        this.caches = caches;
        this.transactions = transactions;
    }

    record SubscriptionView(UUID orgId, String planCode, String planName, String status,
            Instant currentPeriodEnd, Instant trialEndsAt, Map<String, Long> entitlements) {
    }

    @Transactional(readOnly = true)
    SubscriptionView view(UUID orgId) {
        PlanSnapshot plan = resolver.planOf(orgId)
                .orElseThrow(() -> new NotFoundException("No plan catalog is seeded."));
        OrgSubscription subscription = subscriptions.findByOrgId(orgId).orElse(null);
        // On the wire, feature-on is a null VALUE (the documented encoding); the storage sentinel
        // is an implementation detail that must not leak.
        Map<String, Long> entitlements = new LinkedHashMap<>();
        plan.entitlements().forEach((key, value) ->
                entitlements.put(key, value == null || value < 0 ? null : value));
        return new SubscriptionView(orgId, plan.code(), plan.name(),
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
        // The repository, not the catalog port: a WRITE is built on an authoritative read (class note).
        Plan plan = plans.findByCode(normalized)
                .orElseThrow(() -> new NotFoundException("No plan named '" + normalized + "'."));
        String previous = resolver.planOf(orgId).map(PlanSnapshot::code).orElse(null);
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
        if (FREE_PLAN.equals(plan.getCode())) {
            throw new ValidationException("The FREE plan has nothing to trial — trials are for paid plans.");
        }
        int days = trialDays <= 0 ? DEFAULT_TRIAL_DAYS : trialDays;
        Instant endsAt = clock.instant().plus(Duration.ofDays(days));
        String previous = resolver.planOf(orgId).map(PlanSnapshot::code).orElse(null);
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
     * The plan CODE behind each lapsed row — one {@link PlanCatalog} read per DISTINCT plan id, because
     * this is a cross-tier read (tenant rows naming platform catalog) and the port is the seam ADR 0011
     * §6 routes it through. Each read is a 60 s-cached GLOBAL entry, and a sweep names at most a
     * handful of distinct plans, so this is not the 1+N it resembles. An unknown plan id is simply
     * absent from the map — the same null the event carried before.
     */
    private Map<UUID, String> planCodesOf(List<OrgSubscription> lapsed) {
        Map<UUID, String> codes = new java.util.HashMap<>();
        lapsed.stream()
                .map(OrgSubscription::getPlanId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(planId -> catalog.plan(planId)
                        .ifPresent(plan -> codes.put(planId, plan.code())));
        return codes;
    }

    java.util.List<Plan> catalog() {
        return plans.findAllByOrderByRankAsc();
    }

    private static final Set<String> KNOWN_ENTITLEMENTS = Set.of(
            EntitlementKeys.MEMBERS_MAX, EntitlementKeys.WEBHOOKS_MAX, EntitlementKeys.EXCHANGE_ENABLED,
            EntitlementKeys.EXCHANGE_SCHEDULES_MAX, EntitlementKeys.API_REQUESTS_PER_MINUTE);

    /**
     * No {@code plan-catalog} eviction here, and its absence is deliberate: the catalog cache does not
     * cache absences ({@code unless = "#result == null"}), so no entry can exist for a code or id that
     * did not resolve — a freshly created plan is visible on the very next catalog read.
     */
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

    /**
     * Editing a plan changes what EVERY org on it may do, so the per-org entitlement cache has to go —
     * but only for those orgs.
     *
     * <h3>Why this is no longer {@code allEntries}, and no longer {@code @Transactional}</h3>
     *
     * <p>{@code org-entitlements} is a TENANT cache now (ADR 0010 §3.5): its keys carry the tenant, so
     * {@code allEntries = true} means "throw away every tenant's answer in order to invalidate the
     * tenants on one plan". It stayed correct and got more wasteful with every tenant added, and the
     * waste is not abstract — it is a cold entitlement read on the next request of every organization in
     * the installation, caused by an operator editing a plan most of them are not on.
     *
     * <p>Naming the affected orgs means reading {@code org_subscription}, which is tenant-tier, from a
     * method whose own writes are the platform-tier catalog. That is two axes, and the schema is chosen
     * when the connection is BORROWED — so the pin has to happen with no transaction open, which an
     * {@code @Transactional} annotation makes impossible: the proxy opens the transaction before the
     * body can pin anything, and {@code TenantContext} throws rather than silently leaving the statements
     * on the previous axis. Hence the explicit {@link TransactionTemplate} (AGENTS §4.3's rule about
     * self-invocation is the same shape seen from the other side). The eviction then runs strictly AFTER
     * the commit, which also closes the window where a concurrent reader could re-cache the pre-commit
     * entitlements behind an eviction that had already happened.
     */
    Plan updatePlan(String code, String name, int rank, Map<String, Long> entitlements) {
        Plan plan = transactions.execute(status -> {
            Plan existing = requirePlan(normalizeCode(code));
            existing.update(name.trim(), rank, toStored(entitlements));
            plans.save(existing);
            auditLog.record("subscription.plan_updated", null, existing.getCode(), null,
                    "name=" + existing.getName() + " rank=" + rank);
            return existing;
        });
        // The catalog snapshot strictly BEFORE the per-org entitlements: a resolve racing the second
        // eviction re-reads the catalog, and evicting in this order means it re-reads the edited plan
        // rather than re-caching the old snapshot behind an entitlement eviction already spent.
        evictCatalogSnapshotOf(plan);
        evictEntitlementsOfOrgsOn(plan);
        return plan;
    }

    /**
     * Drops the cached entitlement map of every organization this plan edit re-gates.
     *
     * <p><b>FREE is the exception, and it is not a shortcut.</b> An org with no {@code org_subscription}
     * row at all runs on FREE — {@link EntitlementResolver#resolve} falls back to it — and those orgs
     * are by definition absent from the plan's subscription rows, so a keyed sweep over those rows would
     * miss exactly the population FREE governs. Editing FREE genuinely does re-gate every tenant, so
     * clearing every tenant is the correct answer there rather than the lazy one.
     */
    private void evictEntitlementsOfOrgsOn(Plan plan) {
        if (FREE_PLAN.equals(plan.getCode())) {
            caches.getCache(EntitlementResolver.CACHE).clear();
            return;
        }
        // Pinned OUTSIDE any transaction (see updatePlan): the repository opens its own inside this
        // axis, so the borrow lands on the schema where org_subscription actually lives.
        List<UUID> affected =
                TenantContext.callAs(POOLED_TENANT, () -> subscriptions.orgIdsByPlanId(plan.getId()));
        for (UUID orgId : affected) {
            // evictForTenant rather than a pin per org: this thread names the tenant it reaches into,
            // which is what a deliberate cross-tenant cache write should look like at the call site.
            caches.evictForTenant(EntitlementResolver.CACHE, orgId, TenantCacheKeys.wholeTenant());
        }
    }

    /**
     * No ENTITLEMENT eviction here, and its absence is the point: the guard below refuses the delete
     * unless NO organization is on the plan, so no per-org entitlement anywhere was derived from it.
     * The {@code allEntries} evict this used to carry threw away every tenant's entitlements to
     * invalidate nothing at all. The CATALOG snapshot is different — {@code planByCode} could hold this
     * plan for any caller — so that one is dropped, after the commit for the same reason
     * {@link #updatePlan}'s evictions run there (a pre-commit evict lets a concurrent reader re-cache
     * the doomed row behind it), which is why this is a {@link TransactionTemplate} and no longer an
     * annotation.
     */
    void deletePlan(String code) {
        Plan plan = transactions.execute(status -> {
            Plan existing = requirePlan(normalizeCode(code));
            if (FREE_PLAN.equals(existing.getCode())) {
                throw new ConflictException("FREE is the default fallback plan and cannot be deleted.");
            }
            if (subscriptions.existsByPlanId(existing.getId())) {
                throw new ConflictException(
                        "This plan is assigned to organizations — reassign them before deleting it.");
            }
            plans.delete(existing);
            auditLog.record("subscription.plan_deleted", null, existing.getCode(),
                    "name=" + existing.getName(), null);
            return existing;
        });
        evictCatalogSnapshotOf(plan);
    }

    /**
     * Drops both remembered forms of one plan — the Spring cache's two keys AND the
     * stale-while-unreachable holdover behind them, because an eviction that cleared the cache but left
     * the holdover would serve the superseded snapshot through the next outage (ADR 0011 §2).
     */
    private void evictCatalogSnapshotOf(Plan plan) {
        org.springframework.cache.Cache cache = caches.getCache(PlanCatalogCache.CACHE);
        cache.evict(PlanCatalogCache.idKey(plan.getId()));
        cache.evict(PlanCatalogCache.codeKey(plan.getCode()));
        planCatalogCache.forget(plan.getId(), plan.getCode());
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
        // Cross-tier read (a tenant row naming the platform catalog) — through the port, like every
        // other one. The code only decorates the event; a 60 s-stale code on a status flip is harmless.
        String planCode = catalog.plan(subscription.getPlanId()).map(PlanSnapshot::code).orElse(null);
        events.publishEvent(new SubscriptionChanged(organizationId,
                planCode, parsed.name(), Instant.now()));
        auditLog.record("subscription.status_changed", organizationId, organizationId.toString(),
                "status=" + before, "status=" + parsed.name());
    }
}
