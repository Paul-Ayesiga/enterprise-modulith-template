package ug.co.smsone.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.shared.cache.PlatformUnreachableException;
import ug.co.smsone.shared.cache.TwoLevelCacheManager;
import ug.co.smsone.shared.persistence.TenantMigrationRunner;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantAxisExtension;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * ADR 0011 §2 end-to-end, against a Postgres whose connectivity is genuinely CUT mid-test — which is
 * why this class pays for its own container and context instead of extending
 * {@code AbstractIntegrationTest}: the singleton database is shared by every cached context in the
 * JVM, and severing it would fail the rest of the suite, not this class.
 *
 * <p>The outage is real, not mocked (ADR 0003: no fakes, no mocked DataSource):
 * {@code ALTER DATABASE … ALLOW_CONNECTIONS false} refuses every new connection and
 * {@code pg_terminate_backend} kills the pooled ones, so Hikari's borrow fails exactly the way it
 * fails when the platform database is gone — the connection-shaped failure §2.2 defines, produced by
 * the whole real stack (driver → Hikari → Spring's translation), which is precisely the chain the
 * classifier must recognize. {@code StaleWhileUnreachableTest} pins the arithmetic with a
 * hand-stepped clock; this class pins the wiring.
 *
 * <h2>The assertion the phase hangs on (AGENTS §5.5)</h2>
 *
 * <p>{@link #aRevokedMembershipDeniesTheVeryNextRequestWhileThePlatformIsUnreachable} is written to go
 * RED if anyone gives {@code OrgAuthorization} a stale-while-unreachable layer, and the falsifiability
 * is structural: the revocation evicts the {@code org-permissions} cache (the production evictor's
 * guaranteed end state), so the only place a "yes" could still live is a holdover the eviction does
 * not reach — exactly the thing a copied-in stale layer would add. The unrevoked member in the same
 * test is the control: their CACHED answer still serves through the outage, proving the revoked
 * member's denial is the revocation's, not the outage's blanket failure.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ExtendWith({TenantAxisExtension.class, TenantSilos.class})
class PlatformUnreachableIntegrationTest {

    /**
     * Short enough to cross inside a test without minutes of sleeping, long enough that the
     * cut-then-probe sequences (each probe costs up to the 1 s borrow timeout below) cannot cross it
     * by accident. ADR 0011 §4.1 says lower than PT15M is legal — this is that knob, exercised.
     */
    private static final String TEST_CEILING = "PT8S";

    @ServiceConnection
    // Not closed for the reason AbstractIntegrationTest documents at its own container field: the
    // class-level context caches against it and Ryuk reaps it at JVM exit.
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    static {
        POSTGRES.start();
        // The real runner, not TenantSchemaBootstrap: that helper's once-per-JVM guard belongs to the
        // SINGLETON container, and going through it here would either no-op (leaving this database
        // unmigrated) or steal the singleton's turn. Same call, own guard-free invocation.
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        TenantMigrationRunner.Manifest manifest =
                TenantMigrationRunner.fromClasspath(dataSource, 2).run(TenantMigrationRunner.Mode.MIGRATE);
        if (!manifest.ok()) {
            throw new IllegalStateException("could not build this test's own database: " + manifest.report());
        }
    }

    @DynamicPropertySource
    static void outageTuning(DynamicPropertyRegistry registry) {
        // The borrow timeout IS the unreachable detector (ADR 0011 §4.1); at the production default
        // every probe in this class would stall 30 s. 1 s keeps the outage sequences inside the
        // ceiling's budget while still exercising the full borrow-validate-retry-timeout path.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "250");
        registry.add("app.tenancy.identity-stale.ceiling", () -> TEST_CEILING);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonLookup personLookup;

    @Autowired
    private OrgLookup orgLookup;

    @Autowired
    private OrgAuthorization orgAuthorization;

    @Autowired
    private TwoLevelCacheManager caches;

    @Autowired
    private ug.co.smsone.shared.cache.StaleWhileUnreachable stale;

    /**
     * Whatever a test did to connectivity, the next one starts with a healthy database — and a pool
     * that can actually borrow again. The wait is not decoration: after a string of failed creation
     * attempts Hikari backs off between retries, so the first borrow after {@code allow_connections
     * true} can still time out at the 1 s detector — and the silo sweep running right behind this
     * method then fails the TEST on the fixture rather than on the contract (observed intermittently
     * when this class runs in sequence with others).
     */
    @AfterEach
    void restoreConnectivity() throws Exception {
        allowConnections(true);
        DataSource pool = jdbc.getDataSource();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (true) {
            try (java.sql.Connection probe = pool.getConnection()) {
                return;
            } catch (RuntimeException | java.sql.SQLException stillWarming) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException(
                            "the pool did not recover within 15 s of allow_connections true", stillWarming);
                }
                Thread.sleep(200);
            }
        }
    }

    /**
     * This class is the first in the suite whose context sits on a database OTHER than the singleton,
     * and building it installed a {@code TenantRoutes} that resolves every cached context's tenant
     * routes against THIS container — where no later test's placement rows exist, so every tenant a
     * later cached-context class places would route to {@code tenant_pool}: ADR 0010 §1's misroute,
     * failing files that name neither this class nor the cause. Point the static resolver back at the
     * database the cached contexts actually describe. {@code TenantRoutesTestInstalls} carries the
     * full argument.
     */
    @AfterAll
    static void putTheRouteResolverBackOnTheSingletonDatabase() {
        ug.co.smsone.shared.tenancy.TenantRoutesTestInstalls
                .reinstallReadingFrom(ug.co.smsone.testsupport.AbstractIntegrationTest.POSTGRES);
    }

    @Test
    void identityLookupsServeTheLastKnownAnswerWhileThePlatformIsUnreachable() throws Exception {
        String subject = "kc-stale-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        String externalOrgId = "kc-org-" + UUID.randomUUID();
        String alias = "ext-" + UUID.randomUUID();
        UUID orgId = EdgeSeed.organization(jdbc, externalOrgId, alias);

        // The authoritative read that arms the holdover — a genuine loader run, not a cache hit.
        assertThat(personLookup.personId(EdgeSeed.ISSUER, subject)).contains(personId);
        assertThat(orgLookup.organizationId(EdgeSeed.ISSUER, externalOrgId, alias)).contains(orgId);

        cutTheDatabase();
        // Force the next lookups past L1 (60 s TTL would otherwise answer them without ever reaching
        // the outage path this test exists to exercise). L2 is off in tests, so clear() is L1-only.
        caches.getCache("person-by-subject").clear();
        caches.getCache("org-by-external-id").clear();

        assertThat(personLookup.personId(EdgeSeed.ISSUER, subject))
                .as("the last-known person is served while the authority cannot be asked (ADR 0011 §2)")
                .contains(personId);
        assertThat(orgLookup.organizationId(EdgeSeed.ISSUER, externalOrgId, alias))
                .as("the last-known org translation is served — it names a tenant whose grants still"
                        + " resolve from the tenant's own side, never an authorization decision")
                .contains(orgId);
    }

    @Test
    void theCeilingDeniesFromTheEntrysOwnAgeNotTheOutageDuration() throws Exception {
        String subject = "kc-ceiling-" + UUID.randomUUID();
        EdgeSeed.person(jdbc, subject);
        assertThat(personLookup.personId(EdgeSeed.ISSUER, subject)).isPresent();

        // The entry ages past the ceiling while the database is perfectly HEALTHY — nothing refreshes
        // it because nothing evicted it (a hit runs no loader). The outage then begins a moment ago,
        // and the answer is already too old to serve: §2.3's measurement, end to end.
        Thread.sleep(java.time.Duration.parse(TEST_CEILING).plusMillis(600).toMillis());
        cutTheDatabase();
        caches.getCache("person-by-subject").clear();

        assertThatThrownBy(() -> personLookup.personId(EdgeSeed.ISSUER, subject))
                .as("an outage seconds old must still deny an entry whose last refresh is older than"
                        + " the ceiling — grace belongs to the entry, not the outage")
                .isInstanceOf(PlatformUnreachableException.class);
    }

    @Test
    void anErasedIdentityIsNotResurrectedByTheStalePath() throws Exception {
        String subject = "kc-erased-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        assertThat(personLookup.personId(EdgeSeed.ISSUER, subject)).contains(personId);

        // Erase the link while the database is UP, the way erasure leaves the world: the row is
        // soft-deleted and the resolution cache is dropped (PersonResolver.forget is the production
        // call; the clear below is its guaranteed end state, reachable from this package).
        jdbc.update("update external_identity set deleted_at = now(), version = version + 1"
                + " where external_subject = ?", subject);
        caches.getCache("person-by-subject").clear();
        // The next authoritative read answers ABSENT — an answer, not an error (§2.2) — and it
        // REPLACES the holdover entry. Absences are deliberately not cached, so no clear is needed
        // before the outage probe below.
        assertThat(personLookup.personId(EdgeSeed.ISSUER, subject)).isEmpty();

        cutTheDatabase();

        assertThat(personLookup.personId(EdgeSeed.ISSUER, subject))
                .as("the stale path repeats the erasure; serving the pre-erasure id would un-erase a"
                        + " person for up to the ceiling (ADR 0011 §2.2)")
                .isEmpty();
    }

    /**
     * The STRUCTURAL half of AGENTS §5.5, and it needs no outage: authorization holds no last-known
     * answer at all, so there is nothing for a revocation to have to race. The tests around it prove
     * the behaviour through a real severed database; this one names the wiring, because the wiring is
     * what a future edit changes — one {@code stale.holdoverFor(CACHE)} line in {@code
     * PermissionResolver}, copying an idiom three other caches already use, and a revoked member keeps
     * their grant for the ceiling. That line would leave every behavioural test above still green
     * until an outage happened to coincide with a revocation.
     *
     * <p>The three identity/catalog names are asserted PRESENT in the same breath, so the check cannot
     * pass by the holdover map simply being empty — a lazily-wired or never-constructed
     * {@code StaleWhileUnreachable} would otherwise satisfy "org-permissions is absent" vacuously.
     */
    @Test
    void authorizationHoldsNoLastKnownAnswerWhileIdentityAndTheCatalogDo() {
        assertThat(stale.heldCaches())
                .as("the caches that may serve a last-known answer: identity translations and the"
                        + " installation-wide plan catalog, and nothing else")
                .contains("person-by-subject", "org-by-external-id", "plan-catalog")
                .as("ADR 0011 §2.1 / AGENTS §5.5: an authorization decision has no ceiling and no"
                        + " grace, so org-permissions must never hold one — an eviction cannot reach a"
                        + " holdover, so a revoked grant would outlive the revocation")
                .doesNotContain("org-permissions", "org-entitlements", "TenantRoutes");
    }

    @Test
    void aRevokedMembershipDeniesTheVeryNextRequestWhileThePlatformIsUnreachable() throws Exception {
        String externalOrgId = "kc-org-" + UUID.randomUUID();
        String alias = "ext-" + UUID.randomUUID();
        UUID orgId = EdgeSeed.organization(jdbc, externalOrgId, alias);
        String revokedSubject = "kc-revoked-" + UUID.randomUUID();
        UUID revoked = EdgeSeed.person(jdbc, revokedSubject);
        UUID kept = EdgeSeed.person(jdbc, "kc-kept-" + UUID.randomUUID());
        UUID revokedRole = EdgeSeed.member(jdbc, orgId, revoked, "READER_A");
        UUID keptRole = EdgeSeed.member(jdbc, orgId, kept, "READER_B");
        grantMemberRead(orgId, revokedRole);
        grantMemberRead(orgId, keptRole);

        // Warm: both members hold member:read and the whole request path proves it over HTTP. The
        // token is minted NOW, while the database can answer the helper's lookups — token() reads
        // external_identity/external_organization, and a mint attempted mid-outage would fail the
        // test on the fixture rather than on the contract under test.
        JwtRequestPostProcessor revokedToken = token(revoked, orgId);
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(revokedToken))
                .andExpect(status().isOk());
        assertThat(orgAuthorization.permissions(kept, orgId)).contains("member:read");

        // Revoke, while the platform is still healthy. The production path (MemberService.remove →
        // MemberRemoved → OrgPermissionCacheEvictor) ends in exactly this state: the membership row
        // gone and org-permissions CLEARED — the async listener is the mechanism, this is its
        // guaranteed end state, written directly so the test does not race it.
        TenantContext.runAs(orgId, () -> jdbc.update(
                "delete from membership where org_id = ? and person_id = ?", orgId, revoked));
        caches.getCache("org-permissions").clear();
        // The control's cached "yes" is rebuilt fresh AFTER the clear, before the outage — the state
        // every untouched member on every pod is in when the platform drops.
        assertThat(orgAuthorization.permissions(kept, orgId)).contains("member:read");

        cutTheDatabase();

        // THE assertion AGENTS §5.5 hangs the phase on. The port may refuse by throwing (the
        // fresh read failed and nothing answers for it — absence-of-fresh-answer) or by an answer
        // without the grant; what it may NEVER do is produce the revoked "yes" from anything
        // remembered. If OrgAuthorization ever grows a stale-while-unreachable holdover, the eviction
        // above does not reach it, the pre-revocation grant is served here, and this goes red.
        Set<String> served = null;
        RuntimeException refusal = null;
        try {
            served = orgAuthorization.permissions(revoked, orgId);
        } catch (RuntimeException failure) {
            refusal = failure;
        }
        if (refusal == null) {
            assertThat(served)
                    .as("a revoked membership must not be resurrected by anything cached or held over")
                    .doesNotContain("member:read");
        } else {
            // The refusal must be the CONTRACT's refusal and not merely "something threw". Without
            // this the branch accepts any breakage — an NPE, a lost axis, a raw
            // CannotCreateTransactionException — as if it were the deliberate 503, and the edge would
            // then render an INTERNAL_ERROR while this test still called it a pass. Its sibling
            // RemoteTenantPlatformOutageTest already pins the type; the two must not disagree.
            assertThat(refusal).isInstanceOf(PlatformUnreachableException.class);
        }

        // The control: the UNREVOKED member's bounded cache entry still answers through the outage —
        // so the revoked member's denial above is the revocation's doing, not the outage's.
        assertThat(orgAuthorization.permissions(kept, orgId))
                .as("the unrevoked member's cached answer survives, which is what makes the revoked"
                        + " member's denial attributable to the revocation")
                .contains("member:read");

        // And over HTTP: the very next request by the revoked member is denied, not served.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(revokedToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("the very next request after a revocation is denied even mid-outage")
                        .isGreaterThanOrEqualTo(400));
    }

    @Test
    void pastTheCeilingTheWireAnswerIs503WithRetryAfterNeverAWrongDenial() throws Exception {
        String externalOrgId = "kc-org-" + UUID.randomUUID();
        String alias = "ext-" + UUID.randomUUID();
        UUID orgId = EdgeSeed.organization(jdbc, externalOrgId, alias);
        String subject = "kc-503-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        grantMemberRead(orgId, EdgeSeed.member(jdbc, orgId, personId, "READER_C"));

        // Minted before the outage — token() reads the database, and the probe below runs after the cut.
        JwtRequestPostProcessor memberToken = token(personId, orgId);
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(memberToken))
                .andExpect(status().isOk());

        // Past the ceiling, mid-outage, with nothing cached: resolution can neither answer nor serve.
        Thread.sleep(java.time.Duration.parse(TEST_CEILING).plusMillis(600).toMillis());
        cutTheDatabase();
        caches.getCache("person-by-subject").clear();
        caches.getCache("org-by-external-id").clear();

        // ADR 0011 §2.3: the shape is TenantSchemaFloor's — "this system cannot currently answer for
        // you" — in the envelope, with Retry-After. 401 would blame the token, 403 would claim the
        // caller is known and refused, ACCOUNT_NOT_PROVISIONED would tell a paying user they don't
        // exist. All three are false, and all three are asserted against, not just the happy 503.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(memberToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errors[0].code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    /**
     * The role needs the permission the warm request exercises; EdgeSeed's role carries none. The
     * value is the enum NAME — {@code role_permission} is {@code @Enumerated(STRING)} of
     * {@code Permission}, so the column holds {@code MEMBER_READ} while the wire, the assertions and
     * every {@code hasPermission} check speak {@code member:read}. A wire code in the column reads
     * back as "No enum constant", which fails the warm 200s before any outage is even cut.
     */
    private void grantMemberRead(UUID orgId, UUID roleId) {
        TenantContext.runAs(orgId, () -> jdbc.update(
                "insert into role_permission (role_id, permission) values (?, 'MEMBER_READ')", roleId));
    }

    /** The OrgRbacApiTest token idiom: iss + linked subject + the PROVIDER's org id under its alias. */
    private JwtRequestPostProcessor token(UUID personId, UUID orgId) {
        String subject = jdbc.queryForObject(
                "select external_subject from external_identity where person_id = ? and deleted_at is null",
                String.class, personId);
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return jwt().jwt(jwt -> jwt.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }

    /**
     * A real outage, from the server side: refuse new connections, then kill the established ones so
     * the pool cannot coast on what it already holds. Reversible, which {@code container.stop()} is
     * not — and reversibility is what lets {@link #restoreConnectivity} hand the next test a healthy
     * database on the same ports.
     */
    private static void cutTheDatabase() throws Exception {
        allowConnections(false);
        psql("select pg_terminate_backend(pid) from pg_stat_activity where datname = '"
                + POSTGRES.getDatabaseName() + "' and pid <> pg_backend_pid()");
    }

    private static void allowConnections(boolean allowed) throws Exception {
        psql("alter database " + POSTGRES.getDatabaseName() + " with allow_connections " + allowed);
    }

    /** Maintenance runs against the always-present `postgres` database — the app one may be refusing. */
    private static void psql(String sql) throws Exception {
        org.testcontainers.containers.Container.ExecResult result = POSTGRES.execInContainer(
                "psql", "-U", POSTGRES.getUsername(), "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("psql failed (" + result.getExitCode() + "): "
                    + result.getStderr() + result.getStdout());
        }
    }
}
