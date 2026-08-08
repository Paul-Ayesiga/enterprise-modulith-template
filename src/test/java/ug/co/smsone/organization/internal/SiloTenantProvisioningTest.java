package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.ProviderOrgMembership;
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.shared.tenancy.placement.TenantProvisioner;
import ug.co.smsone.shared.tenancy.placement.TenantProvisioningException;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The policy where the ordering is not free (ADR 0010 §4.3): every new tenant gets its own schema, so
 * something has to be BUILT before the tenant can be announced.
 *
 * <h2>The hazard under test</h2>
 *
 * <p>{@code OrganizationRegistered} carries three after-commit listeners — the trial
 * ({@code org_subscription}), the billing account ({@code billing_account}) and the search document —
 * and all three are asynchronous and all three write TENANT-tier tables. Create the schema after the
 * announcement and all three race it: every new tenant silently gets no trial and no billing account,
 * and because those listeners are outbox-retried the failures look like transient noise rather than a
 * signup path that does not work.
 *
 * <p><strong>So the assertion here is about ORDER, not about the end state.</strong> A test that
 * checked only that the schema exists afterwards would pass just as happily if the schema had been
 * created a second after the event went out — which is precisely the bug. {@link Announcements} is a
 * listener that records what a listener could actually SEE at the two instants that matter: when the
 * event is published, and when the commit releases it. Both observations must already show a schema
 * that exists and is migrated.
 */
@TestPropertySource(properties = "app.tenancy.placement.policy=silo-per-org")
class SiloTenantProvisioningTest extends AbstractIntegrationTest {

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private TenantProvisioner provisioner;

    @Autowired
    private TenantPlacements placements;

    @Autowired
    private Announcements announcements;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KeycloakOrgAdminGateway keycloakOrg;

    @MockitoBean
    private PersonProvisioning personProvisioning;

    @MockitoBean
    private ProviderOrgMembership providerOrgMembership;

    @BeforeEach
    void keycloakSaysYes() {
        announcements.reset();
        given(keycloakOrg.findOrganizationIdByAlias(any())).willReturn(Optional.empty());
        given(keycloakOrg.createOrganization(any(), any()))
                .willAnswer(call -> "kc-" + call.getArgument(0));
        given(personProvisioning.provision(any()))
                .willAnswer(call -> new ProvisionedPerson(UUID.randomUUID(), "owner@test", false));
    }

    /**
     * <strong>Silo schemas must not outlive the test that made them.</strong> Two other classes read the
     * catalogue directly and would fail on a leftover: {@code TenancyTierBoundaryTest} asserts nothing
     * lives outside {@code platform} and {@code tenant_pool}, and the migration runner's fleet discovery
     * takes {@code select distinct schema_name from platform.tenant_placement}, so a placement pointing
     * at a dropped schema is reported as a broken tenant. Both would fail in another file, for a reason
     * that would look nothing like this one.
     */
    @AfterEach
    void dropEverySiloThisTestCreated() {
        List<String> silos = jdbc.queryForList(
                "select schema_name from information_schema.schemata where schema_name ~ '^t_[0-9a-f]{32}$'",
                String.class);
        silos.forEach(silo -> jdbc.execute("drop schema " + silo + " cascade"));
        jdbc.update("delete from platform.tenant_placement where schema_name ~ '^t_[0-9a-f]{32}$'");
    }

    /**
     * The ordering rule, asserted from the vantage of the listeners it protects: at the instant
     * {@code OrganizationRegistered} was published, and again at the instant the commit released it,
     * the tenant's schema already existed and was already at head.
     */
    @Test
    void theSchemaIsBuiltAndMigratedBeforeAnyListenerCouldObserveTheTenant() {
        String alias = "silo-" + UUID.randomUUID().toString().substring(0, 8);

        UUID orgId = organizations.create(alias, "Silo " + alias, "owner@test", "Ada", null)
                .organization().getId();
        String silo = TenantSchemas.siloSchema(orgId);

        assertThat(announcements.count())
                .as("the tenant was announced exactly once — with nothing to observe, everything below"
                        + " would pass vacuously")
                .isEqualTo(1);
        assertThat(announcements.atPublish())
                .as("what a synchronous listener saw the moment the event was published")
                .isEqualTo(new Seen(true, headOf(silo)));
        assertThat(announcements.afterCommit())
                .as("and what the after-commit listeners — the trial, the billing account, the search"
                        + " document — were released into")
                .isEqualTo(new Seen(true, headOf(silo)));
        assertThat(headOf(silo)).as("a schema that exists but is at no version cannot serve").isNotNull();

        TenantPlacement placement = placements.find(orgId).orElseThrow();
        assertThat(placement.schemaName()).isEqualTo(silo);
        assertThat(placement.state()).isEqualTo(PlacementState.ACTIVE);
        assertThat(placement.schemaVersion())
                .as("the registry's version is the schema's real head, not a guess")
                .isEqualTo(headOf(silo));
    }

    /**
     * <strong>The routing gap this phase deliberately leaves open, stated as an assertion so it cannot
     * be forgotten.</strong> Phase 4 makes the schema, the migration and the registry real; Phase 5 is
     * where {@code TenantSchemas.searchPathFor} starts reading the placement and a promoted tenant's
     * queries actually land in its own schema. Until then a silo is provisioned and recorded but not yet
     * routed to, which is why {@code silo-per-org} is a measurement flag and not a production mode.
     *
     * <p>When Phase 5 lands, this test fails — and that is the point. Delete it then, and assert the
     * silo instead.
     */
    @Test
    void routingStillSendsAProvisionedSiloTenantToThePoolUntilPhase5() {
        UUID orgId = UUID.randomUUID();
        provisioner.provisionHomeFor(orgId);

        assertThat(placements.find(orgId).orElseThrow().schemaName())
                .isEqualTo(TenantSchemas.siloSchema(orgId));
        assertThat(TenantSchemas.searchPathFor(Tenant.of(orgId)))
                .as("ADR 0010 Phase 5 flips this — when it does, this assertion is the thing that says so")
                .startsWith(TenantSchemas.TENANT_POOL);
    }

    /**
     * A provision that fails leaves a FAILED row naming the reason — a {@code select}, not an incident
     * somebody reconstructs from the logs of a pod that has since been replaced (ADR 0010 §4.2).
     *
     * <p><strong>The failure is real and so is its shape.</strong> The silo is pre-loaded with a stray
     * table — which is exactly what a promotion that died between "create the schema" and "migrate it"
     * leaves behind (§6 hop 0→1) — and Flyway refuses to migrate a non-empty schema that has no history
     * table of its own. Nothing is applied, so nothing is half-applied: that is what
     * {@link PlacementState#FAILED} promises, and the retry at the end is the proof that it means "not
     * finished" rather than "dead".
     */
    @Test
    void aFailedProvisionLeavesAQueryableFailedRowAndNeverAnnouncesTheTenant() {
        UUID orgId = UUID.randomUUID();
        String silo = TenantSchemas.siloSchema(orgId);
        jdbc.execute("create schema " + silo);
        jdbc.execute("create table " + silo + ".left_behind (id uuid)");

        assertThatThrownBy(() -> provisioner.provisionHomeFor(orgId))
                .as("the signup that asked for this tenant must hear about it — a provisioner that"
                        + " swallowed this would announce a tenant with no schema")
                .isInstanceOf(TenantProvisioningException.class);

        TenantPlacement placement = placements.find(orgId).orElseThrow(
                () -> new AssertionError("a failed provision that recorded nothing is the incident this"
                        + " registry exists to prevent"));
        assertThat(placement.state()).isEqualTo(PlacementState.FAILED);
        assertThat(placement.schemaName()).isEqualTo(silo);
        assertThat(placement.lastError())
                .as("the reason travels with the row, so the query answers the question on its own"
                        + " rather than sending its reader to the logs of a replaced pod")
                .isNotBlank()
                .contains(silo);
        assertThat(placement.isActive())
                .as("ACTIVE is what says a tenant was announced — this one never was")
                .isFalse();
        assertThat(placements.findByState(PlacementState.FAILED))
                .extracting(TenantPlacement::orgId)
                .contains(orgId);
        assertThat(announcements.count()).isZero();
        assertThat(jdbc.queryForObject("select to_regclass(cast(? as text)) is not null",
                Boolean.class, silo + ".flyway_schema_history"))
                .as("not one migration was applied — the schema is at no version rather than at half of"
                        + " one (ADR 0010 §4.2 forbids executeInTransaction=false for this reason)")
                .isFalse();

        // FAILED is resumable, and this is the half that says so: fix what broke, call the same method,
        // and the same row is re-claimed rather than a second tenant being created.
        jdbc.execute("drop table " + silo + ".left_behind");
        provisioner.provisionHomeFor(orgId);

        TenantPlacement healed = placements.find(orgId).orElseThrow();
        assertThat(healed.state())
                .as("provisioned, and still unannounced — announcing is the tenant write's word, not"
                        + " the provisioner's")
                .isEqualTo(PlacementState.PROVISIONING);
        assertThat(healed.schemaVersion()).isNotNull();
        assertThat(healed.lastError()).as("a stale reason must not read as a current one").isNull();
    }

    /** The head version of a schema, or null when it has never been migrated. */
    private String headOf(String schema) {
        List<String> head = jdbc.queryForList(
                "select version from " + schema + ".flyway_schema_history"
                        + " where success and version is not null order by installed_rank desc limit 1",
                String.class);
        return head.isEmpty() ? null : head.getFirst();
    }

    /** What a listener could see about a tenant's schema: does it exist, and what version is it at. */
    record Seen(boolean schemaExists, String head) {
    }

    /**
     * Stands where the three real listeners stand and writes down what they would have seen.
     *
     * <p>Two vantages, because they answer different questions. The synchronous {@code @EventListener}
     * fires at the {@code publishEvent} call itself, inside the tenant's transaction — the earliest
     * moment anything can observe the announcement. The {@code AFTER_COMMIT} one fires where
     * {@code @ApplicationModuleListener} does, which is where the trial, the billing account and the
     * search document actually run. If provisioning ever moved after the announcement, the first would
     * report a missing schema and the second would be a coin toss.
     */
    static class Announcements {

        private final JdbcTemplate jdbc;
        private volatile Seen atPublish;
        private volatile Seen afterCommit;
        private volatile int count;

        Announcements(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @EventListener
        void whenPublished(OrganizationRegistered event) {
            count++;
            atPublish = look(event.orgId());
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void whenReleased(OrganizationRegistered event) {
            afterCommit = look(event.orgId());
        }

        void reset() {
            atPublish = null;
            afterCommit = null;
            count = 0;
        }

        Seen atPublish() {
            return atPublish;
        }

        Seen afterCommit() {
            return afterCommit;
        }

        int count() {
            return count;
        }

        /**
         * Never throws, and that is a requirement rather than politeness: the publish-time observation
         * runs INSIDE the transaction being observed, so a statement that errored would abort it and
         * turn an ordering failure into a failed commit that named nothing. {@code to_regclass}
         * answers "is there a history table here" with a NULL rather than an error, which is what lets
         * "the schema is missing" and "the schema is at no version" both come back as observations.
         */
        private Seen look(UUID orgId) {
            String silo = TenantSchemas.siloSchema(orgId);
            Boolean migrated = jdbc.queryForObject("select to_regclass(cast(? as text)) is not null",
                    Boolean.class, silo + ".flyway_schema_history");
            Integer schema = jdbc.queryForObject(
                    "select count(*) from information_schema.schemata where schema_name = ?",
                    Integer.class, silo);
            boolean exists = schema != null && schema > 0;
            if (migrated == null || !migrated) {
                return new Seen(exists, null);
            }
            List<String> head = jdbc.queryForList(
                    "select h.version from " + silo + ".flyway_schema_history h"
                            + " where h.success and h.version is not null"
                            + " order by h.installed_rank desc limit 1",
                    String.class);
            return new Seen(exists, head.isEmpty() ? null : head.getFirst());
        }
    }

    @TestConfiguration
    static class Observer {

        @Bean
        Announcements announcements(JdbcTemplate jdbc) {
            return new Announcements(jdbc);
        }
    }
}
