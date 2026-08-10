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
import org.junit.jupiter.api.extension.RegisterExtension;
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
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantRoutesTestInstalls;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantAxisExtension;
import ug.co.smsone.testsupport.TenantRemotes;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <strong>ADR 0010 §7 Phase 7's degradation ladder, on a tenant served from a SECOND database while
 * the platform database is genuinely unreachable</strong>: the silo keeps serving on cached identity
 * inside the ceiling; authorization is never served stale in any rung — a revocation denies the very
 * next request mid-outage; at the ceiling identity denies too (503 + {@code Retry-After}, never a
 * wrong 401/403); and reconnection recovers without a restart. {@code PlatformUnreachableIntegrationTest}
 * proves the same contracts for a PRIMARY-served tenant, where an outage eventually fails everything;
 * this class proves the availability the own-database hop actually buys — the tenant's rows are on a
 * database that never went down, so whole requests keep serving, not just cached port answers.
 *
 * <h2>How the outage is made, and why this way</h2>
 *
 * <p>The primary is cut server-side — {@code ALTER DATABASE … ALLOW_CONNECTIONS false} plus
 * {@code pg_terminate_backend} on every established backend — rather than by pausing or stopping the
 * container. Chosen deliberately: it is reversible on the same ports (a stopped container loses its
 * mapped port, and this class must recover mid-class); it fails borrows FAST with a server refusal
 * (a paused container leaves TCP connects hanging until the full connection timeout, which turns
 * every probe into a stall); and the failure travels the whole real chain — driver → Hikari →
 * Spring's translation — which is exactly the connection-shaped classification the stale layer must
 * recognize (ADR 0011 §2.2). That is also why this class pays for its own container and context
 * instead of extending {@link AbstractIntegrationTest}: severing the shared singleton would fail
 * every other cached context in the JVM.
 *
 * <h2>What is pinned about routes here, and what deliberately is not</h2>
 *
 * <p>{@code app.tenancy.route-ttl} is raised to PT10M for this context. Routes are the one fact ADR
 * 0011 §2.4 forbids serving past its TTL when the registry is unreachable — a refusal, 503, because a
 * misroute is 0010 §1's worst failure — and that refusal is already pinned where routing is the
 * subject ({@code TenantRemoteRoutingTest}, {@code TenantRoutesTest}). Here the route memo must
 * OUTLIVE the outage, or every rung of the ladder collapses into the same blanket 503 and the
 * identity/authorization asymmetry this class exists to prove becomes unfalsifiable. One test still
 * exercises the refusal end to end — by forgetting the route mid-outage, which is what TTL expiry
 * does — so the ladder's floor is asserted, not assumed.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ExtendWith({TenantAxisExtension.class, TenantSilos.class})
class RemoteTenantPlatformOutageTest {

    /** The identity ceiling, small enough to cross inside a test — the PlatformUnreachable knob. */
    private static final String TEST_CEILING = "PT8S";

    @ServiceConnection
    // Not closed for the reason AbstractIntegrationTest documents at its own container field.
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    static {
        POSTGRES.start();
        // The real runner, its own guard-free invocation — the PlatformUnreachableIntegrationTest
        // reasoning: TenantSchemaBootstrap's once-per-JVM guard belongs to the singleton container.
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
        TenantRemotes.register(registry);
        // The borrow timeout IS the unreachable detector (ADR 0011 §4.1); at the production default
        // every probe here would stall 30 s.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "250");
        registry.add("app.tenancy.identity-stale.ceiling", () -> TEST_CEILING);
        // See the class note: the route memo must outlive the outage or the ladder is unfalsifiable.
        registry.add("app.tenancy.route-ttl", () -> "PT10M");
    }

    /**
     * This context sits on its own database, so its {@code TenantRoutes} — with this class's PT10M
     * TTL — won the process-wide static slot. Hand the slot back to the singleton database (and the
     * suite's PT2S TTL) or every later cached-context class resolves routes against THIS container.
     * {@link TenantRoutesTestInstalls} carries the full argument.
     */
    @AfterAll
    static void putTheRouteResolverBackOnTheSingletonDatabase() {
        TenantRoutesTestInstalls.reinstallReadingFrom(AbstractIntegrationTest.POSTGRES);
    }

    @RegisterExtension
    final TenantRemotes remotes = new TenantRemotes();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonLookup personLookup;

    @Autowired
    private OrgAuthorization orgAuthorization;

    @Autowired
    private TwoLevelCacheManager caches;

    @Autowired
    private DataSource appDataSource;

    /** Whatever a test did to connectivity, the next one starts with a healthy database. */
    @AfterEach
    void restoreConnectivity() throws Exception {
        reconnect();
    }

    /**
     * Reopens the database AND waits until the app's own pool can actually borrow again. The wait is
     * not decoration: after a string of failed creation attempts Hikari backs off between retries, so
     * the first borrow after {@code allow_connections true} can still time out at the 1 s detector —
     * and a recovery assertion (or the silo sweep in teardown) racing that window fails on the
     * fixture, not on the contract.
     */
    private void reconnect() throws Exception {
        allowConnections(true);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (true) {
            try (java.sql.Connection probe = appDataSource.getConnection()) {
                return;
            } catch (RuntimeException | java.sql.SQLException stillWarming) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("the pool did not recover within 15 s of"
                            + " allow_connections true", stillWarming);
                }
                Thread.sleep(200);
            }
        }
    }

    /**
     * The ladder's first two rungs in one sequence, because the second is only meaningful against the
     * first: the silo SERVES on stale identity (whole requests, 200, mid-outage — the new claim), and
     * inside that same outage a revocation still denies the very next request, because authorization
     * resolves from the tenant's own healthy database and is never held over (AGENTS §5.5; ADR 0011
     * §2.1's never-stale row). The unrevoked member is the control on both sides of the revocation.
     */
    @Test
    void theSiloServesOnCachedIdentityWhileARevocationStillDeniesTheVeryNextRequest() throws Exception {
        Seat seat = remoteOrgWithTwoMembers();

        // Warm over HTTP: real requests, so the identity holdovers and the route memo are the state
        // every pod is in when a platform database drops.
        mockMvc.perform(members(seat, seat.keptSubject())).andExpect(status().isOk());
        mockMvc.perform(members(seat, seat.revokedSubject())).andExpect(status().isOk());

        cutTheDatabase();
        // Force the next identity resolutions past L1's 60 s TTL so they actually face the outage —
        // the loader must run, fail connection-shaped, and serve the holdover (ADR 0011 §2.2).
        caches.getCache("person-by-subject").clear();
        caches.getCache("org-by-external-id").clear();

        mockMvc.perform(members(seat, seat.keptSubject()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        assertThat(personLookup.personId(EdgeSeed.ISSUER, seat.keptSubject()))
                .describedAs("the identity rung: last-known person served while the authority is down")
                .contains(seat.keptPerson());

        // The revocation, mid-outage. The membership row lives on the REMOTE database, which is
        // healthy — that is the whole point of the hop — and the evictions below are the production
        // evictor's guaranteed end state, written directly so the assertions do not race the async
        // listener. Every assertion IS the very next request: no sleep, no retry.
        TenantContext.runAs(seat.orgId(), () -> jdbc.update(
                "delete from membership where org_id = ? and person_id = ?",
                seat.orgId(), seat.revokedPerson()));

        // The eviction is applied KEYED FIRST — the revoked member's entry only — and that ordering is
        // the assertion's whole evidentiary value. Production's evictor is a clear() spanning every
        // member, which mid-outage leaves NOBODY holding a cached answer, so "the revoked member gets
        // 503" after a clear is a claim an implementation that simply denied every org-permissions
        // MISS during an outage would satisfy vacuously — the shape this project has shipped twice
        // (Phase 5's retention row asserted while the job never ran; Phase 6's gate that passed with
        // the bundler deleted). A keyed eviction is strictly WEAKER than the clear production sends
        // (a clear evicts a superset), so a denial under it implies the denial under production's —
        // and it leaves the kept member's cached "yes" standing as the control below.
        TenantContext.runAs(seat.orgId(), () -> caches.getCache("org-permissions")
                .evict(seat.orgId() + ":" + seat.revokedPerson()));

        // The wire answer is the REFUSAL, not a 403: with the platform down the org's own standing
        // (platform.organization, the suspended-org half of the resolve) cannot be confirmed, and
        // ADR 0011 §2.1 gives authorization no ceiling and no grace — so an uncached resolve refuses
        // (503 + Retry-After, nothing cached) rather than answering off anything remembered. What it
        // may NEVER be is a 2xx: the revoked "yes" served from a holdover is the failure AGENTS §5.5
        // exists to forbid.
        mockMvc.perform(members(seat, seat.revokedSubject()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                // WHICH 503 — and this is the discriminating half, not decoration. The identity
                // ceiling renders the same status, the same Retry-After and the same envelope
                // ("a platform-side lookup … has aged out"), so a bare isServiceUnavailable() here
                // would pass just as well if the holdovers happened to cross this class's PT8S
                // ceiling a second earlier: the revoked member's refusal would then be the OUTAGE's
                // and the revocation would be untested, which is the vacuous shape this project has
                // shipped twice. Pinning PermissionResolver's own detail makes the denial
                // attributable to the org-status read that had no fresh answer — absence-of-answer,
                // never a remembered yes. (Details reach the wire verbatim: no error.* key is
                // seeded, so ErrorDetailLocalizer falls back to the author's text.)
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString(
                        "This organization's standing cannot be confirmed")));
        // THE DISCRIMINATING CONTROL. Same outage, same instant, one entry evicted and one not: the
        // kept member's bounded cache entry still answers, so the 503 above is the REVOCATION's doing
        // and not the outage's. Asked at the port rather than over HTTP on purpose — a cache hit costs
        // no borrow, whereas another mid-outage request would spend two 1 s borrow timeouts on the
        // identity legs and age the holdovers toward this class's PT8S ceiling, which would make the
        // control the thing that broke the ladder.
        assertThat(TenantContext.callAs(seat.orgId(),
                        () -> orgAuthorization.permissions(seat.keptPerson(), seat.orgId())))
                .describedAs("an unrevoked member's cached answer survives the same outage — without"
                        + " this a blanket deny-everything-on-miss implementation passes vacuously")
                .contains("member:read");

        // And now production's actual end state, the clear() spanning every member, so the port check
        // below judges the coarse eviction the evictor really sends.
        caches.getCache("org-permissions").clear();
        // At the port: the builders' either-refuse-or-deny pattern — a throw is absence-of-fresh-
        // answer, an answer must lack the grant; a remembered "member:read" fails the build.
        Set<String> served = null;
        RuntimeException refusal = null;
        try {
            served = TenantContext.callAs(seat.orgId(),
                    () -> orgAuthorization.permissions(seat.revokedPerson(), seat.orgId()));
        } catch (RuntimeException failure) {
            refusal = failure;
        }
        if (refusal == null) {
            assertThat(served)
                    .describedAs("authorization must never resurrect a revoked grant from anything"
                            + " cached or held over")
                    .doesNotContain("member:read");
        } else {
            assertThat(refusal).isInstanceOf(PlatformUnreachableException.class);
        }

        // Attribution, on reconnection: the production evictor is a clear() spanning every member, so
        // after the revocation the kept member's next MISS refuses like any uncached resolve until
        // the platform answers again (fail closed — access lost, never gained). The moment it does,
        // the same instant serves the kept member and denies the revoked one with a clean FORBIDDEN:
        // the denial is the revocation's, not the outage's, and it survived the outage.
        reconnect();
        mockMvc.perform(members(seat, seat.keptSubject())).andExpect(status().isOk());
        mockMvc.perform(members(seat, seat.revokedSubject())).andExpect(status().isForbidden());
    }

    /**
     * The ladder's floor and its recovery: past the ceiling the identity holdover is DENIED — 503 +
     * {@code Retry-After} in the envelope, the {@code TenantSchemaFloor} shape, never 401 (the token
     * is fine), never 403 (the caller is not known-and-refused), never {@code ACCOUNT_NOT_PROVISIONED}
     * (they exist) — and reconnecting the platform recovers the same member without a restart.
     */
    @Test
    void atTheCeilingIdentityDeniesWith503AndReconnectionRecovers() throws Exception {
        Seat seat = remoteOrgWithTwoMembers();
        mockMvc.perform(members(seat, seat.keptSubject())).andExpect(status().isOk());

        cutTheDatabase();
        caches.getCache("person-by-subject").clear();
        caches.getCache("org-by-external-id").clear();

        // Age the entry past the ceiling DURING the outage: staleness is measured per entry from its
        // last successful refresh (the warm request above), so the sleep ages exactly that.
        Thread.sleep(java.time.Duration.parse(TEST_CEILING).plusMillis(600).toMillis());

        assertThatThrownBy(() -> personLookup.personId(EdgeSeed.ISSUER, seat.keptSubject()))
                .isInstanceOf(PlatformUnreachableException.class);
        mockMvc.perform(members(seat, seat.keptSubject()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errors[0].code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.meta.requestId").exists());

        reconnect();
        mockMvc.perform(members(seat, seat.keptSubject()))
                .andExpect(status().isOk());
    }

    /**
     * The rung deliberately BELOW identity: a route this process no longer remembers cannot be
     * re-read with the registry down, and the request fails closed rather than being served off a
     * guessed pool — ADR 0011 §2.4's asymmetry, exercised by forgetting the memo, which is precisely
     * what TTL expiry does. The failure is loud (5xx in the envelope), never a 2xx served against a
     * guessed schema, and reconnection heals it because the next resolution re-reads the registry.
     */
    @Test
    void aRouteThisProcessNoLongerRemembersFailsClosedRatherThanGuessing() throws Exception {
        Seat seat = remoteOrgWithTwoMembers();
        mockMvc.perform(members(seat, seat.keptSubject())).andExpect(status().isOk());

        cutTheDatabase();
        TenantRoutes.forget(seat.orgId());

        // The refusal escapes as the router's own typed failure rather than a rendered status: the
        // first borrow on the org's axis happens inside a FILTER (the policy filter at @Order 3), a
        // filter that throws bypasses GlobalExceptionHandler, and MockMvc surfaces servlet-chain
        // exceptions to the caller instead of rendering a container 500. What is being pinned is the
        // half that matters: the request DIED — loudly, naming the refusal-to-guess — and was never
        // served off a guessed pool.
        assertThatThrownBy(() -> mockMvc.perform(members(seat, seat.keptSubject())))
                .hasStackTraceContaining("could not read the route of organization")
                .hasStackTraceContaining(seat.orgId().toString());

        reconnect();
        mockMvc.perform(members(seat, seat.keptSubject())).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ fixture

    /** One remote-served org, two seated members, both holding {@code member:read}. */
    private record Seat(UUID orgId, String externalOrgId, String alias,
            UUID keptPerson, String keptSubject, UUID revokedPerson, String revokedSubject) {
    }

    private Seat remoteOrgWithTwoMembers() {
        String externalOrgId = "kc-org-" + UUID.randomUUID();
        String alias = "ext-" + UUID.randomUUID();
        UUID orgId = EdgeSeed.organization(jdbc, externalOrgId, alias);
        remotes.placeOnRemote(orgId);
        String keptSubject = "kc-kept-" + UUID.randomUUID();
        String revokedSubject = "kc-revoked-" + UUID.randomUUID();
        UUID kept = EdgeSeed.person(jdbc, keptSubject);
        UUID revoked = EdgeSeed.person(jdbc, revokedSubject);
        memberOnRemote(orgId, kept, "LADDER_KEPT");
        memberOnRemote(orgId, revoked, "LADDER_REVOKED");
        return new Seat(orgId, externalOrgId, alias, kept, keptSubject, revoked, revokedSubject);
    }

    /**
     * Not {@link EdgeSeed#member}: that fixture writes {@code platform.org_membership_index} INSIDE
     * the tenant axis — one transaction, correct for every primary-served tenant — but a remote
     * tenant's axis borrows from a database whose {@code platform} schema deliberately holds only the
     * outbox (ADR 0011 §5.1), so the qualified insert dies on the tripwire. The split here is the
     * same split production makes for remote tenants: tenant rows on the tenant's axis, the routing
     * index as its own platform-axis write.
     */
    private void memberOnRemote(UUID orgId, UUID personId, String roleCode) {
        UUID roleId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> {
            jdbc.update("""
                    insert into org_role (id, org_id, code, name, system_role, version, created_at)
                    values (?, ?, ?, ?, false, 0, now())
                    """, roleId, orgId, roleCode, roleCode);
            jdbc.update("""
                    insert into membership (id, org_id, person_id, role_id, status, version, created_at)
                    values (?, ?, ?, ?, 'ACTIVE', 0, now())
                    """, UUID.randomUUID(), orgId, personId, roleId);
            // The enum NAME: role_permission is @Enumerated(STRING) of Permission, so the column
            // holds MEMBER_READ while the wire and every hasPermission check speak member:read.
            jdbc.update("insert into role_permission (role_id, permission) values (?, 'MEMBER_READ')",
                    roleId);
        });
        TenantContext.runAsPlatform(() -> jdbc.update("""
                insert into platform.org_membership_index (person_id, org_id, status)
                values (?, ?, 'ACTIVE') on conflict (person_id, org_id) do nothing
                """, personId, orgId));
    }

    private static org.springframework.test.web.servlet.RequestBuilder members(Seat seat, String subject) {
        return get("/api/v1/orgs/{orgId}/members", seat.orgId()).with(token(seat, subject));
    }

    /** The OrgRbacApiTest token idiom: iss + linked subject + the PROVIDER's org id under its alias. */
    private static JwtRequestPostProcessor token(Seat seat, String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(seat.alias(), Map.of("id", seat.externalOrgId()))));
    }

    // ------------------------------------------------------------------ the outage

    /**
     * A real outage, from the server side: refuse new connections, then kill the established ones so
     * the pool cannot coast on what it already holds. See the class note for why this and not a
     * container pause or stop.
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
