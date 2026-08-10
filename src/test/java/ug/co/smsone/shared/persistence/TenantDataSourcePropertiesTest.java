package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ug.co.smsone.shared.persistence.TenantDataSourceProperties.Definition;
import ug.co.smsone.shared.persistence.TenantDataSourceProperties.Pool;

/**
 * ADR 0011 §4.1's config vocabulary, enforced where the house rule puts it: the record's compact
 * constructor, so a bad value fails the boot and not a tenant at 04:00. Pure record arithmetic — no
 * container, because nothing here touches one: the pools these definitions become are built (and
 * refuse to guess) in {@code TenantDataSources}, whose behaviour is covered on real databases by
 * {@code TenantRemoteRoutingTest}.
 */
class TenantDataSourcePropertiesTest {

    /** The shipped default: no entries, no pools, routing byte-for-byte what it was before ADR 0011. */
    @Test
    void anEmptyMapIsLegalAndIsTheDefault() {
        assertThat(new TenantDataSourceProperties(null).datasources()).isEmpty();
        assertThat(new TenantDataSourceProperties(Map.of()).datasources()).isEmpty();
    }

    /**
     * {@code primary} is the platform pool — {@code spring.datasource} — and a second definition of it
     * would be two truths about one database. Refused at startup, naming the key.
     */
    @Test
    void thePrimaryNameIsReservedAndRefused() {
        assertThatThrownBy(() -> new TenantDataSourceProperties(
                Map.of("primary", complete())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary")
                .hasMessageContaining("reserved");
    }

    /** The name is what a placement row selects; one that cannot be typed twice is a future outage. */
    @Test
    void aNameOutsideThePatternIsRefused() {
        for (String bad : new String[] {"Analytics", "1eu", "-eu", "a".repeat(33), "eu west", ""}) {
            assertThatThrownBy(() -> new TenantDataSourceProperties(Map.of(bad, complete())))
                    .describedAs("name '%s'", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a legal tenant datasource name");
        }
    }

    /** A half-credentialed remote is a per-tenant outage discovered by the tenant; fail the boot instead. */
    @Test
    void urlUsernameAndPasswordAreEachRequiredAndTheFailureNamesTheKey() {
        assertThatThrownBy(() -> new TenantDataSourceProperties(Map.of("eu",
                new Definition(null, "user", "secret", null))))
                .hasMessageContaining("app.tenancy.datasources.eu.url");
        assertThatThrownBy(() -> new TenantDataSourceProperties(Map.of("eu",
                new Definition("jdbc:postgresql://db/x", " ", "secret", null))))
                .hasMessageContaining("app.tenancy.datasources.eu.username");
        assertThatThrownBy(() -> new TenantDataSourceProperties(Map.of("eu",
                new Definition("jdbc:postgresql://db/x", "user", "", null))))
                .hasMessageContaining("app.tenancy.datasources.eu.password");
    }

    /**
     * The three defaults are the ADR's numbers, and the connection-timeout is load-bearing beyond pool
     * tuning: it is the "unreachable" detector (§2.2), so zero — which asks Hikari for its 30 s
     * default — is refused rather than inherited.
     */
    @Test
    void poolNumbersDefaultToTheAdrsAndRefuseTheDangerousShapes() {
        Pool defaults = new TenantDataSourceProperties(Map.of("eu", complete()))
                .datasources().get("eu").pool();
        assertThat(defaults.maximumPoolSize()).isEqualTo(8);
        assertThat(defaults.minimumIdle()).isZero();
        assertThat(defaults.connectionTimeout()).isEqualTo(Duration.ofSeconds(5));

        assertThatThrownBy(() -> new Pool(0, null, null))
                .hasMessageContaining("maximum-pool-size");
        assertThatThrownBy(() -> new Pool(4, 5, null))
                .hasMessageContaining("minimum-idle");
        assertThatThrownBy(() -> new Pool(null, null, Duration.ZERO))
                .hasMessageContaining("connection-timeout");
    }

    /** ADR 0011 §10 Q3: past ten, the connection budget needs measuring, not another entry. */
    @Test
    void theEleventhDatasourceIsRefused() {
        Map<String, Definition> eleven = new HashMap<>();
        for (int i = 0; i < 11; i++) {
            eleven.put("ds-" + i, complete());
        }
        assertThatThrownBy(() -> new TenantDataSourceProperties(eleven))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cap is 10");
    }

    private static Definition complete() {
        return new Definition("jdbc:postgresql://db.example/tenants", "svc", "secret", null);
    }
}
