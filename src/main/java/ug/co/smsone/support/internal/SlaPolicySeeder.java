package ug.co.smsone.support.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds per-priority SLA targets (create-if-absent — a deployment that tuned them keeps them). P1
 * is tightest; P4 loosest. An org's plan could tighten these (an ENTERPRISE entitlement) — that
 * override is a documented future seam; the seeded set is the default every ticket starts from.
 */
@Component
class SlaPolicySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SlaPolicySeeder.class);

    private final SlaPolicyRepository policies;

    SlaPolicySeeder(SlaPolicyRepository policies) {
        this.policies = policies;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("P1", 30, 4 * 60);       // 30m first response, 4h resolution
        seed("P2", 2 * 60, 8 * 60);
        seed("P3", 8 * 60, 3 * 24 * 60);
        seed("P4", 24 * 60, 7 * 24 * 60);
    }

    private void seed(String priority, int firstResponse, int resolution) {
        if (policies.findByPriority(priority).isEmpty()) {
            policies.save(SlaPolicy.of(priority, firstResponse, resolution));
            log.info("Seeded SLA policy {} ({}m first response, {}m resolution)", priority, firstResponse, resolution);
        }
    }
}
