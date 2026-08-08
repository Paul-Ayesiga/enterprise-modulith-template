package ug.co.smsone.support.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Seeds per-priority SLA targets (create-if-absent — a deployment that tuned them keeps them). P1
 * is tightest; P4 loosest. An org's plan could tighten these (an ENTERPRISE entitlement) — that
 * override is a documented future seam; the seeded set is the default every ticket starts from.
 */
@Component
class SlaPolicySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SlaPolicySeeder.class);

    private final SlaPolicyRepository policies;
    private final TransactionTemplate transactions;

    SlaPolicySeeder(SlaPolicyRepository policies, TransactionTemplate transactions) {
        this.policies = policies;
        this.transactions = transactions;
    }

    /**
     * Declares the platform axis, then opens the transaction inside it — the same swap, for the same
     * reason, as {@code subscription.internal.PlanSeeder}: an {@code ApplicationRunner} runs on the boot
     * thread with nothing to pin an axis for it, and {@code @Transactional} on this method would have
     * borrowed the connection (and chosen the schema) before any line of the body could (ADR 0010 §3.2,
     * §3.4). One transaction around all four seeds, exactly as before.
     */
    @Override
    public void run(ApplicationArguments args) {
        TenantContext.runAsPlatform(() -> transactions.executeWithoutResult(tx -> {
            seed("P1", 30, 4 * 60);       // 30m first response, 4h resolution
            seed("P2", 2 * 60, 8 * 60);
            seed("P3", 8 * 60, 3 * 24 * 60);
            seed("P4", 24 * 60, 7 * 24 * 60);
        }));
    }

    private void seed(String priority, int firstResponse, int resolution) {
        if (policies.findByPriority(priority).isEmpty()) {
            policies.save(SlaPolicy.of(priority, firstResponse, resolution));
            log.info("Seeded SLA policy {} ({}m first response, {}m resolution)", priority, firstResponse, resolution);
        }
    }
}
