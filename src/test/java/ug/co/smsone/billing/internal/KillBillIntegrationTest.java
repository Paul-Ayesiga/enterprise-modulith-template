package ug.co.smsone.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The Kill Bill wire, for real (the template rule: infra-touching code meets a real container —
 * this is the files/Keycloak IT's sibling): tenant + simple-plan bootstrap, idempotent account
 * resolution by externalKey, a subscription that lands ACTIVE on the created plan, and the
 * invoice read. Kill Bill boots slowly (JRuby + schema init), hence the generous startup timeout;
 * the containers are static singletons so the whole class pays it once.
 */
class KillBillIntegrationTest extends AbstractIntegrationTest {

    static final Network NETWORK = Network.newNetwork();

    static final GenericContainer<?> KILLBILL_DB =
            new GenericContainer<>("killbill/mariadb:0.24")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("killbill-db")
                    .withEnv("MYSQL_ROOT_PASSWORD", "killbill");

    static final GenericContainer<?> KILLBILL =
            new GenericContainer<>("killbill/killbill:0.24.10")
                    .withNetwork(NETWORK)
                    .withExposedPorts(8080)
                    .withEnv("KILLBILL_DAO_URL", "jdbc:mysql://killbill-db:3306/killbill")
                    .withEnv("KILLBILL_DAO_USER", "root")
                    .withEnv("KILLBILL_DAO_PASSWORD", "killbill")
                    .withEnv("KILLBILL_SERVER_TEST_MODE", "true")
                    .waitingFor(Wait.forHttp("/1.0/healthcheck").forPort(8080)
                            .withStartupTimeout(Duration.ofMinutes(5)));

    static {
        KILLBILL_DB.start();
        KILLBILL.dependsOn(KILLBILL_DB);
        KILLBILL.start();
    }

    @DynamicPropertySource
    static void killBillUrl(DynamicPropertyRegistry registry) {
        registry.add("app.billing.base-url",
                () -> "http://" + KILLBILL.getHost() + ":" + KILLBILL.getMappedPort(8080));
    }

    @Autowired
    private KillBillGateway gateway;

    @Test
    void tenantPlansAccountsAndSubscriptionsRoundTripAgainstRealKillBill() {
        gateway.ensureTenant();
        gateway.ensureTenant(); // idempotent: the 409 resolves silently
        gateway.ensureSimplePlan("pro-monthly", "Pro", new BigDecimal("49.00"));

        UUID orgId = UUID.randomUUID();
        UUID accountId = gateway.ensureAccount(orgId, "org-" + orgId);
        assertThat(gateway.ensureAccount(orgId, "org-" + orgId))
                .as("externalKey resolution is idempotent").isEqualTo(accountId);
        assertThat(gateway.findAccountByExternalKey(orgId)).contains(accountId);

        UUID subscriptionId = gateway.createSubscription(accountId, "pro-monthly");
        assertThat(subscriptionId).isNotNull();
        assertThat(gateway.subscriptions(accountId))
                .anySatisfy(sub -> {
                    assertThat(sub.planName()).isEqualTo("pro-monthly");
                    assertThat(sub.state()).isEqualTo("ACTIVE");
                });

        assertThat(gateway.accountView(accountId).currency()).isNotBlank();
        assertThat(gateway.invoices(accountId)).isNotNull(); // auto-invoicing depends on KB config
    }
}
