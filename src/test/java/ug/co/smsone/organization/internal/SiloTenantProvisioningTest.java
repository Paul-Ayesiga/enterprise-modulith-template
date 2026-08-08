package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.ProviderOrgMembership;
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.shared.persistence.TenantMigrationRunner;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Manifest;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Mode;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.shared.tenancy.placement.TenantProvisioner;
import ug.co.smsone.shared.tenancy.placement.TenantProvisioningException;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.TenantSilos;

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

    /**
     * <strong>Silo schemas must not outlive the test that made them.</strong> Two other classes read the
     * catalogue directly and would fail on a leftover: {@code TenancyTierBoundaryTest} asserts nothing
     * lives outside {@code platform} and {@code tenant_pool}, and the migration runner's fleet discovery
     * takes {@code select distinct schema_name from platform.tenant_placement}, so a placement pointing
     * at a dropped schema is reported as a broken tenant. Both would fail in another file, for a reason
     * that would look nothing like this one.
     *
     * <p>The sweep itself lives in {@link TenantSilos} — one mechanism, shared with the mixed-tenancy
     * fixture Phase 5 builds on — and it runs as an extension rather than an {@code @AfterEach} so it is
     * still guaranteed after a test that threw somewhere this class does not control.
     */
    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    private static final String REGISTERED = OrganizationRegistered.class.getName();

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private OrgProjectionWriter projectionWriter;

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

        // The registry half, term for term with TenantPlacementOnCreateTest's pooled assertion — same
        // four columns, one of them different. Written out rather than narrowed to "the interesting
        // one" so the two classes read as one claim under two policies: a new tenant gets an ACTIVE
        // placement, on the primary datasource, naming the home ITS policy chose, and is announced once.
        TenantPlacement placement = placements.find(orgId).orElseThrow(
                () -> new AssertionError("a tenant with no placement row is a tenant nothing can route"));
        assertThat(placement.schemaName()).isEqualTo(silo);
        assertThat(placement.dataSourceName()).isEqualTo(TenantPlacement.PRIMARY_DATASOURCE);
        assertThat(placement.state()).isEqualTo(PlacementState.ACTIVE);
        assertThat(placement.schemaVersion())
                .as("the registry's version is the schema's real head, not a guess")
                .isEqualTo(headOf(silo));
    }

    /**
     * <strong>The routing claim, and it is the whole of Phase 5's headline.</strong> Phase 4 made the
     * schema, the migration and the registry real while every axis still resolved to
     * {@code tenant_pool} — a silo was provisioned and recorded but not routed to, and this test used to
     * assert exactly that gap so it could not be forgotten. It is now the assertion that the gap is
     * closed: {@code TenantSchemas.searchPathFor} reads {@code platform.tenant_placement} and a placed
     * tenant's connections are set to its own schema.
     *
     * <p><strong>The negative half is the one that matters.</strong> Asserting the path merely starts
     * with the silo would still pass if the pool were on it as a second element — which would put every
     * unqualified tenant table one fallthrough away from five thousand other tenants' rows, and would
     * look correct in any test that only ever reads its own data. {@code ext} is the only other schema
     * allowed on a tenant path, and it holds pg_trgm and nothing else (ADR 0010 §3.1).
     */
    @Test
    void aProvisionedSiloTenantIsRoutedToItsOwnSchemaAndNotToThePool() {
        UUID orgId = UUID.randomUUID();
        provisioner.provisionHomeFor(orgId);
        String silo = TenantSchemas.siloSchema(orgId);

        assertThat(placements.find(orgId).orElseThrow().schemaName()).isEqualTo(silo);
        assertThat(TenantSchemas.searchPathFor(Tenant.of(orgId)))
                .as("the placement is what decides the search_path — that is what makes promotion a row"
                        + " change and not a deployment (ADR 0010 §3.1)")
                .isEqualTo(silo + ", " + TenantSchemas.EXTENSIONS)
                .doesNotContain(TenantSchemas.TENANT_POOL);
    }

    /**
     * And the other side of the same fact: an organization the registry has never heard of routes to the
     * pool. ADR 0010 §4.3 keeps provisioning free of DDL under the shipped policy, so "no placement row"
     * is an ordinary, serving state and not an unknown — a router that refused it would refuse every
     * tenant created before V57's backfill.
     */
    @Test
    void anOrganizationWithNoPlacementRowStillRoutesToThePool() {
        assertThat(TenantSchemas.searchPathFor(Tenant.of(UUID.randomUUID())))
                .isEqualTo(TenantSchemas.TENANT_POOL + ", " + TenantSchemas.EXTENSIONS);
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

    /**
     * <strong>Announced once, ever — asserted on the DEFAULT policy, which is where it stopped being
     * covered.</strong> {@code TenantPlacementOnCreateTest} makes the same two claims and now declares
     * {@code policy=pooled} to make them, so without this pair the guard against a second trial, a second
     * billing account and a second search document had no test on the path every real signup takes.
     *
     * <p>The second half is the re-adopt shape: a Keycloak organization relinked to a local tenant that
     * already exists. It goes through {@code OrgProjectionWriter} on the axis {@code tenantAxisOf}
     * answers with — which for an existing tenant is its real id, and therefore its own silo — because
     * that is what {@code OrganizationService.provisionOwner} does, minus the two provider calls. A
     * regression in either {@code announce}'s or {@code reserve}'s {@code ON CONFLICT … WHERE} arm shows
     * up here as a second publication.
     */
    @Test
    void aSiloTenantIsAnnouncedExactlyOnceAndReAdoptingItAnnouncesNothing() {
        String alias = "silo-once-" + UUID.randomUUID().toString().substring(0, 8);
        String externalOrgId = "kc-" + alias;

        UUID orgId = organizations.create(alias, "Silo " + alias, "owner@test", "Ada", null)
                .organization().getId();

        assertThat(announcements(orgId)).isEqualTo(1);

        UUID again = reAdopt(externalOrgId, alias);

        assertThat(again).as("the same tenant, found by its provider link").isEqualTo(orgId);
        assertThat(announcements(orgId))
                .as("OrganizationRegistered fans out to a trial, a billing account and a search document"
                        + " — a second one is a second of each")
                .isEqualTo(1);
        assertThat(placements.find(orgId).orElseThrow().schemaName())
                .as("and a re-adopt does not move a serving tenant — that is promotion")
                .isEqualTo(TenantSchemas.siloSchema(orgId));
    }

    /**
     * <strong>The failure the shipped default moved onto the ordinary signup path, end to end.</strong>
     *
     * <p>Under {@code POOLED} a signup ran no DDL, so a Flyway failure during provisioning was not a
     * thing that could happen. Under {@code silo-per-org} every signup runs a fresh 28-table sequence, so
     * a transient lock, a full disk or a concurrent DDL leaves the row this test builds: an organization
     * committed by {@code reserve}, a schema created, a migration that did not finish, and
     * {@link PlacementState#FAILED} with no {@code schema_version} — never announced.
     *
     * <p><strong>Then the next deploy's fleet pass finds that schema.</strong> It is in the catalogue
     * because {@code CREATE SCHEMA} succeeded, so {@code TENANT_SCHEMAS_IN_CATALOGUE} discovers it with
     * or without the registry, and it migrates cleanly now that the obstacle is gone. What that pass must
     * NOT do is rescue the placement to ACTIVE: {@code TenantPlacements.announce} publishes only for the
     * call that transitions a row INTO ACTIVE, so a rescued row means this tenant is never announced —
     * no trial, no billing account, no search document — while every health query says it is fine.
     * {@code TenantMigrationRunner.NOT_A_PROVISIONING_FAILURE} is the predicate that keeps the row
     * claimable, and this is the test that would notice it going away.
     */
    @Test
    void aProvisioningFailureRescuedByAMigrationPassIsStillAnnouncedExactlyOnce() {
        String alias = "wedged-" + UUID.randomUUID().toString().substring(0, 8);
        String externalOrgId = "kc-" + alias;
        // The "learn the id" half of a silo signup, on its own transaction, exactly as homeReadyFor
        // commits it before anything is built.
        UUID orgId = TenantContext.callAsPlatform(
                () -> projectionWriter.reserve(externalOrgId, alias, "Org " + alias).getId());
        String silo = TenantSchemas.siloSchema(orgId);
        jdbc.execute("create schema " + silo);
        jdbc.execute("create table " + silo + ".left_behind (id uuid)");

        assertThatThrownBy(() -> provisioner.provisionHomeFor(orgId))
                .isInstanceOf(TenantProvisioningException.class);
        assertThat(announcements(orgId)).isZero();
        assertThat(placements.find(orgId).orElseThrow().schemaVersion())
                .as("nothing proved this home fit, and that null is what tells the fleet runner whose"
                        + " FAILED this is")
                .isNull();

        jdbc.execute("drop table " + silo + ".left_behind");
        migrateTheFleetOver(silo);

        assertThat(placements.find(orgId).orElseThrow().state())
                .as("a migration pass may not announce a tenant nobody announced — ACTIVE here would"
                        + " make announce() decline for the rest of this tenant's life")
                .isEqualTo(PlacementState.FAILED);

        UUID adopted = reAdopt(externalOrgId, alias);

        assertThat(adopted).isEqualTo(orgId);
        assertThat(announcements(orgId))
                .as("the retry is the announcement this tenant never got — once, and only once")
                .isEqualTo(1);
        assertThat(placements.find(orgId).orElseThrow().state()).isEqualTo(PlacementState.ACTIVE);
    }

    /**
     * The local write, run again for a tenant that already exists — {@code OrganizationService}'s
     * re-adopt path minus the Keycloak and identity calls. The axis is ASKED rather than assumed, which
     * for an existing tenant is its own silo; see {@code OrgProjectionWriter}'s note on
     * {@code NEW_POOLED_TENANT} for why driving this method without that question is a misroute.
     */
    private UUID reAdopt(String externalOrgId, String alias) {
        return TenantContext.callAs(projectionWriter.tenantAxisOf(externalOrgId, alias),
                () -> projectionWriter.projectWithOwner(externalOrgId, alias, "Org " + alias,
                        UUID.randomUUID()).getId());
    }

    /**
     * One pass of the real fleet runner over one schema — the Kubernetes Job's own code path, on its own
     * unpooled connections. Not the application's {@code DataSource}: the runner pins a baseline
     * {@code search_path} on every borrow and takes {@code 2 x workers + 2} of them, and the test profile
     * caps the shared pool at three.
     */
    private static void migrateTheFleetOver(String schema) {
        Manifest manifest = TenantMigrationRunner.fromClasspath(
                        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                                POSTGRES.getPassword()), 1)
                .fanOut(Mode.MIGRATE, List.of(schema));
        assertThat(manifest.ok())
                .as("the pass this test is about has to SUCCEED — a failed one would leave the placement"
                        + " FAILED for the wrong reason and prove nothing: %s", manifest.report())
                .isTrue();
    }

    /**
     * How many times this tenant was announced, read from the durable outbox — term for term with
     * {@code TenantPlacementOnCreateTest.announcements}, so the pooled and silo classes count the same
     * thing. Every publication of {@code OrganizationRegistered} leaves a row in
     * {@code platform.event_publication} ({@code SearchEventListeners} takes it with
     * {@code @ApplicationModuleListener}), including one a listener would have missed.
     *
     * <p><strong>A publication leaves one row PER LISTENER, so {@code count(*)} counts listeners and
     * not announcements.</strong> Modulith stores a publication for every AFTER_COMMIT
     * {@code TransactionalApplicationListener} it is about to invoke
     * ({@code PersistentApplicationEventMulticaster.storePublications}) — that is every
     * {@code @TransactionalEventListener}, not only the {@code @ApplicationModuleListener} ones, unless
     * {@code spring.modulith.events.registry-trigger-annotation} narrows it, which nothing here sets.
     * The trial and the billing account are flag-gated and off in the suite, so the outbox holds one
     * row per announcement — {@code SearchEventListeners}' — everywhere except HERE, where
     * {@link Announcements#whenReleased} stands beside it to observe the same instant and is persisted
     * exactly like a production listener. A bare {@code count(*)} therefore reads 2 for a single
     * correct announcement — a fact about the observer this class installed, not about the tenant, and
     * the only reason {@code TenantPlacementOnCreateTest} read 1 from the identical query.
     *
     * <p>Grouping by {@code listener_id} and taking the largest group asks each listener how many
     * announcements it saw; every listener sees every publication, so that is the number of
     * publications however many listeners are registered. It absorbs nothing: a tenant announced twice
     * gives EVERY listener two rows, and this still answers 2.
     */
    private int announcements(UUID orgId) {
        Integer count = jdbc.queryForObject("""
                select coalesce(max(seen), 0) from (
                       select count(*) as seen from platform.event_publication
                        where event_type = ? and serialized_event like ?
                        group by listener_id) per_listener
                """, Integer.class, REGISTERED, "%" + orgId + "%");
        return count == null ? 0 : count;
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
