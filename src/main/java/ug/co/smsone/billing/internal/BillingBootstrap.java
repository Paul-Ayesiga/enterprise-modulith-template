package ug.co.smsone.billing.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev bootstrap (off by default, the org dev-bootstrap pattern): tenant, one simple catalog plan
 * per mapped billable code, and the push-notification callback when a URL is configured — so
 * `docker compose up` yields a Kill Bill that can bill out of the box. Production uploads a real
 * catalog and registers its callback deliberately; this runner is for the template's dev loop.
 */
@Component
@ConditionalOnProperty(name = "app.billing.bootstrap", havingValue = "true")
class BillingBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BillingBootstrap.class);

    private final KillBillGateway killBill;
    private final KillBillProperties properties;

    BillingBootstrap(KillBillGateway killBill, KillBillProperties properties) {
        this.killBill = killBill;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            killBill.ensureTenant();
            properties.planMapping().forEach((code, kbPlan) -> killBill.ensureSimplePlan(
                    kbPlan, code.charAt(0) + code.substring(1).toLowerCase(),
                    properties.planPrices().getOrDefault(code, java.math.BigDecimal.ONE)));
            if (!properties.callbackUrl().isBlank()) {
                killBill.registerNotificationCallback(
                        properties.callbackUrl() + "?token=" + properties.callbackToken());
            }
        } catch (RuntimeException ex) {
            // Boot must not die because the billing stack is down — the org bootstrap's stance.
            log.warn("Kill Bill bootstrap skipped (is the killbill container up?): {}", ex.toString());
        }
    }
}
