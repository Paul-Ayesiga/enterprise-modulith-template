package ug.co.smsone.subscription.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.subscription.EntitlementKeys;

/**
 * Seeds the plan catalog at startup — the RoleSeeder pattern: create-if-absent by code, never
 * overwrite (a deployment that tuned its limits keeps them). FREE's caps are deliberately generous
 * enough that a fresh org is never gate-blocked doing ordinary setup; ENTERPRISE carries only the
 * feature keys — no limit rows IS the encoding for unlimited.
 */
@Component
class PlanSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlanSeeder.class);

    private final PlanRepository plans;
    private final TransactionTemplate transactions;

    PlanSeeder(PlanRepository plans, TransactionTemplate transactions) {
        this.plans = plans;
        this.transactions = transactions;
    }

    /**
     * <b>The transaction is a {@link TransactionTemplate} rather than an annotation for a tenancy
     * reason.</b> {@code search_path} is chosen when the connection is borrowed, so the axis has to be
     * declared before the transaction opens — and {@link TenantContext#setPlatform()} throws inside an
     * active one so the mistake cannot be made quietly (ADR 0010 §3.2). With {@code @Transactional} on
     * this method there was no point in the body early enough to pin: the proxy had already opened the
     * transaction, and every {@code plan} statement would have run against the empty {@code no_tenant}
     * schema. The atomicity is unchanged — one transaction around all three seeds, same propagation,
     * same rollback rules.
     *
     * <p>{@code plan} is a platform-tier catalog and stays on this axis past Phase 2.
     */
    @Override
    public void run(ApplicationArguments args) {
        TenantContext.runAsPlatform(() -> transactions.executeWithoutResult(tx -> {
            seed("FREE", "Free", 0, entitlements(25L, 10L, 5L, 60L));
            seed("PRO", "Pro", 1, entitlements(250L, 50L, 25L, 600L));
            seed("ENTERPRISE", "Enterprise", 2, entitlements(null, null, null, null));
        }));
    }

    /** Feature-on marker in STORAGE. Not null: Hibernate silently drops null-valued map entries on load. */
    static final long FEATURE_ON = -1L;

    private static Map<String, Long> entitlements(Long membersMax, Long webhooksMax, Long schedulesMax,
            Long apiPerMinute) {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put(EntitlementKeys.EXCHANGE_ENABLED, FEATURE_ON); // present = on, for every tier
        if (membersMax != null) {
            map.put(EntitlementKeys.MEMBERS_MAX, membersMax);
        }
        if (webhooksMax != null) {
            map.put(EntitlementKeys.WEBHOOKS_MAX, webhooksMax);
        }
        if (schedulesMax != null) {
            map.put(EntitlementKeys.EXCHANGE_SCHEDULES_MAX, schedulesMax);
        }
        if (apiPerMinute != null) {
            map.put(EntitlementKeys.API_REQUESTS_PER_MINUTE, apiPerMinute);
        }
        return map;
    }

    private void seed(String code, String name, int rank, Map<String, Long> entitlements) {
        if (plans.findByCode(code).isEmpty()) {
            plans.save(Plan.of(code, name, rank, entitlements));
            log.info("Seeded plan {} ({} entitlement keys)", code, entitlements.size());
        }
    }
}
