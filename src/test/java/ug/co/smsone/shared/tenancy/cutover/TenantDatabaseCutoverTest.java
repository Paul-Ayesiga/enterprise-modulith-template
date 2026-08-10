package ug.co.smsone.shared.tenancy.cutover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.shared.cache.TwoLevelCacheManager;
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.cutover.TenantCutovers.State;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.shared.tenancy.promotion.TenantFreezes;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantRemotes;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <strong>ADR 0010 §7 Phase 7's gate, as a test: one organization served ENTIRELY from a second
 * database, moved there by the shipped {@link TenantCutover} over real logical replication — not
 * hand-wired streams — with the freeze window measured, and brought back the same way, because
 * "reversible" unproven is a hope.</strong> The outage half of the gate (cached identity to the
 * ceiling, authorization never stale) lives in {@code RemoteTenantPlatformOutageTest}, which pays for
 * a severable primary of its own; this class keeps the suite's singleton as the source database and
 * {@link TenantRemotes}' container as the destination.
 *
 * <p><b>The verification discipline is {@code TenantPromotionTest}'s, one database over.</b> The
 * cutover's own {@code verified} fingerprints are asserted — they are part of the report's contract —
 * but never trusted alone: every load-bearing count and checksum below is recomputed with SQL this
 * test owns, over a DIRECT connection to the container it is about, because asking the machinery under
 * test whether it agrees with itself proves nothing. {@code select current_database()} is the one
 * routed read used deliberately: the two containers carry different database names precisely so that
 * one answer says which SERVER served the axis.
 *
 * <p><b>This class certifies ONE path: forward, then a real reverse cutover.</b> Everything an
 * incident actually reaches for — {@code rollBack}, {@code abort}, {@code reclaimAbandonedCopy},
 * {@code reclaimAbandonedSlots}, the resume branch and the refusals — is
 * {@link TenantCutoverReversalTest}'s, against the same two containers. The split is by cost: this
 * one seeds a >5k-row tenant so that "the freeze does not scale with tenant weight" is measured
 * against a tenant that weighs something, and that fixture is not worth paying twice.
 *
 * <p><b>The conninfo strings are container-bridge addresses, and the reason is {@link CutoverLinks}'
 * own:</b> a subscription's {@code CONNECTION} is dialled by the database server on the far side, not
 * by this JVM — so the host-mapped port that is right in every JDBC URL here names nothing from inside
 * the other container. Both containers sit on Docker's default bridge, where container-to-container
 * traffic on 5432 is routable by IP (proven against two scratch 18.4 containers before this class was
 * written).
 */
@AutoConfigureMockMvc
class TenantDatabaseCutoverTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @RegisterExtension
    final TenantRemotes remotes = new TenantRemotes();

    @DynamicPropertySource
    static void cutoverTuning(DynamicPropertyRegistry registry) {
        TenantRemotes.register(registry);
        // The watch window is the insurance term between flip and decommission (P7D in production,
        // ADR 0011 §10 Q4). The gate's reverse leg has to DECOMMISSION the stale source copy before it
        // can move the tenant back — a live cutover row refuses a second move — so the term is a
        // second here: long enough that the refusal-before-the-window path stays reachable in other
        // tests, short enough that this one does not sleep a week.
        registry.add("app.tenancy.cutover.watch-window", () -> "PT1S");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantCutover cutover;

    @Autowired
    private TenantCutovers cutovers;

    @Autowired
    private TenantPlacements placements;

    @Autowired
    private TenantFreezes freezes;

    @Autowired
    private TwoLevelCacheManager caches;

    @Autowired
    private OrgAuthorization orgAuthorization;

    // The org under test and its pooled neighbour — fields so the cleanup can name them after a
    // failure anywhere in the sequence (the TenantPromotionTest thaw discipline).
    private UUID orgId;
    private UUID neighbourId;

    /**
     * <strong>Nothing this class froze, half-moved or replicated may outlive it.</strong> The freeze
     * rows and placement rows follow {@code TenantPromotionTest}'s cleanup argument verbatim (a leaked
     * freeze pauses the pooled fleet for every class that runs next). The replication objects are this
     * class's own hazard: a publication, subscription or slot that survives a failed run sits on a
     * container SHARED by every context in the JVM — the subscription's apply worker retries forever
     * against whatever half-state remains, and its slot pins WAL on its publisher for the rest of the
     * run. The schema sweeps ({@link TenantSilos}, {@link TenantRemotes}) run after this method and
     * know nothing about replication, so the drops happen here, escape-hatch first (DISABLE →
     * slot_name=NONE → DROP never dials the far side, so it cannot hang on whatever broke the test),
     * then the orphaned slots both sides.
     */
    @AfterEach
    void releaseEverythingThisClassCouldHaveLeaked() throws Exception {
        try (Connection primary = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Connection remote = TenantRemotes.remoteConnection()) {
            dropCutoverSubscriptions(primary);
            dropCutoverSubscriptions(remote);
            dropCutoverPublicationsAndSlots(primary);
            dropCutoverPublicationsAndSlots(remote);
        }
        for (UUID org : new UUID[] {orgId, neighbourId}) {
            if (org != null) {
                freezes.thaw(org);
                jdbc.update("delete from platform.tenant_placement where org_id = ?", org);
            }
        }
    }

    /**
     * The whole gate as one journey, because each leg's precondition is the previous leg's outcome and
     * a second cutover per test method would double the slowest fixture in the suite. The phases are
     * numbered in comments; every phase ends on assertions.
     */
    @Test
    void oneOrgIsServedEntirelyFromASecondDatabaseAndComesBackTheSameWay() throws Exception {
        // ---- Phase 0: a real tenant, siloed on primary, with real rows across the shapes that can
        // go wrong: rows keyed by org_id, children that reach the org through a parent, the tenant
        // half of a split table, and a subscription whose plan STAYS on primary (the cross-database
        // soft ref every post-move request exercises through EntitlementResolver).
        String extOrgId = "kc-org-" + UUID.randomUUID();
        String alias = "ext-" + UUID.randomUUID();
        orgId = EdgeSeed.organization(jdbc, extOrgId, alias);
        String silo = silos.place(orgId);

        String subjectA = "kc-a-" + UUID.randomUUID();
        String subjectB = "kc-b-" + UUID.randomUUID();
        UUID personA = EdgeSeed.person(jdbc, subjectA);
        UUID personB = EdgeSeed.person(jdbc, subjectB);
        UUID roleA = EdgeSeed.member(jdbc, orgId, personA, "GATE_FIRST");
        UUID roleB = EdgeSeed.member(jdbc, orgId, personB, "GATE_SECOND");
        // Enum NAMES, not wire codes: role_permission is @Enumerated(STRING) of Permission, so the
        // column holds MEMBER_READ while the wire and the @PreAuthorize checks speak member:read.
        grant(orgId, roleA, "MEMBER_READ", "TICKET_READ");
        grant(orgId, roleB, "MEMBER_READ", "TICKET_READ");
        // The fixture pre-assertion every fan-out gate needs: the seeded rows are PHYSICALLY in the
        // silo, or every "the move carried them" assertion below passes against tenant_pool.
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, orgId, "membership", "person_id", personA);

        UUID t1 = ticket(silo, orgId, personA, "oldest", 3);
        UUID t2 = ticket(silo, orgId, personA, "middle", 2);
        UUID t3 = ticket(silo, orgId, personA, "newest", 1);
        message(silo, t1, personA, "first");
        message(silo, t1, personA, "second");
        message(silo, t2, personA, "third");
        jdbc.update("insert into " + q(silo) + ".org_subscription (id, org_id, plan_id, status,"
                + " version, created_at) values (?, ?,"
                + " (select id from platform.plan where code = 'PRO'), 'ACTIVE', 0, now())",
                UUID.randomUUID(), orgId);
        // Bulk rows, so "the freeze is independent of tenant weight" is asserted against a tenant
        // that actually weighs something: the copy of these runs ONLINE, before anything freezes.
        jdbc.update("insert into " + q(silo) + ".audit_log (id, org_id, action, occurred_at, version,"
                + " created_at) select gen_random_uuid(), ?, 'gate.bulk', now(), 0, now()"
                + " from generate_series(1, 5000)", orgId);

        String neighbourSubject = "kc-n-" + UUID.randomUUID();
        String neighbourExtOrg = "kc-org-" + UUID.randomUUID();
        String neighbourAlias = "ext-" + UUID.randomUUID();
        neighbourId = EdgeSeed.organization(jdbc, neighbourExtOrg, neighbourAlias);
        TenantContext.runAsPlatform(() -> placements.announce(neighbourId, TenantSchemas.TENANT_POOL));
        UUID neighbourPerson = EdgeSeed.person(jdbc, neighbourSubject);
        UUID neighbourRole = EdgeSeed.member(jdbc, neighbourId, neighbourPerson, "GATE_NEIGHBOUR");
        grant(neighbourId, neighbourRole, "MEMBER_READ", "TICKET_READ");
        UUID neighbourTicket = ticket(TenantSchemas.TENANT_POOL, neighbourId, neighbourPerson, "pooled", 1);

        // ---- Phase 1: the pre-move truths every post-move assertion compares against.
        assertThat(TenantRoutes.routeOf(orgId))
                .isEqualTo(new TenantRoutes.Route(silo, TenantPlacement.PRIMARY_DATASOURCE));
        Map<String, Long> before = counts(silo, orgId);
        assertThat(before.values()).describedAs("the fixture must seed rows, or every equality below"
                + " is trivially true").allMatch(rows -> rows > 0);
        String ticketSumBefore = checksumOnPrimary(ticketChecksumSql(silo), orgId);
        String messageSumBefore = checksumOnPrimary(messageChecksumSql(silo), orgId);
        Map<String, Long> neighbourBefore = counts(TenantSchemas.TENANT_POOL, neighbourId);

        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk());
        MvcResult firstPage = mockMvc.perform(get("/api/v1/orgs/{orgId}/tickets", orgId)
                        .param("page[size]", "1").with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(t3.toString()))
                .andExpect(jsonPath("$.meta.page.nextCursor").isNotEmpty())
                .andReturn();
        // The cursor minted while the tenant lived on PRIMARY. Keyset cursors are values from the
        // rows (createdAt desc, id desc), not positions in a database, which is exactly what the
        // post-move assertion is about.
        String cursorFromPrimary = JsonPath.read(
                firstPage.getResponse().getContentAsString(), "$.meta.page.nextCursor");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/tickets", orgId)
                        .param("page[size]", "1").param("page[after]", cursorFromPrimary)
                        .with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(t2.toString()));

        // ---- Phase 2: the move, through the SHIPPED machinery.
        CutoverLinks forward = new CutoverLinks(
                bridgeConninfo(POSTGRES), bridgeConninfo(TenantRemotes.container()));
        CutoverReport out = cutover.moveToDatasource(orgId, TenantRemotes.DATASOURCE, forward);

        // The measured freeze: the §7.2 claim is "seconds, independent of tenant weight". The lower
        // bound proves the post-flip route-TTL hold was actually taken — skipping it is the
        // stale-route defect step 8 exists to prevent, because nothing pushes a flip and every other
        // process keeps addressing this tenant's borrows to the OLD pool until its own memo expires.
        // The upper bound is the phase gate's own number — minutes here mean the copy leaked into the
        // freeze.
        assertThat(out.freezeHeld())
                .describedAs("writes were held %s against a >5k-row tenant", out.freezeHeld())
                .isGreaterThanOrEqualTo(TenantRoutes.routeTtl())
                .isLessThan(Duration.ofSeconds(60));
        assertThat(out.rowsMoved()).isGreaterThan(5000L);
        assertThat(out.warnings()).isEmpty();
        // The report's per-table fingerprints must agree with this test's own counts — the report is
        // the operator's evidence, so its numbers are part of the contract, not decoration.
        for (Map.Entry<String, Long> entry : withoutAudit(before).entrySet()) {
            assertThat(out.verified().get(entry.getKey()).rows())
                    .describedAs("report.verified[%s]", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
        assertThat(out.nonEmpty()).containsKeys("membership", "org_role", "role_permission",
                "ticket", "ticket_message", "org_subscription", "audit_log");

        // Where the tenant now IS: the route names the pair, the org axis answers from the other
        // server, the platform axis never left primary.
        assertThat(TenantRoutes.routeOf(orgId))
                .isEqualTo(new TenantRoutes.Route(silo, TenantRemotes.DATASOURCE));
        assertThat(TenantContext.callAs(orgId,
                () -> jdbc.queryForObject("select current_database()", String.class)))
                .isEqualTo("remotedb");
        assertThat(TenantContext.callAsPlatform(
                () -> jdbc.queryForObject("select current_database()", String.class)))
                .isEqualTo(POSTGRES.getDatabaseName());
        assertThat(TenantContext.callAsPlatform(() -> cutovers.find(orgId)).orElseThrow().state())
                .isEqualTo(State.WATCHING);

        // The physical proof, independently recomputed on a DIRECT remote connection: same counts,
        // same content checksums, and no tenant_pool there at all — the destination cannot even hold
        // a misroute.
        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(countsOn(remote, silo, orgId)).isEqualTo(before);
            assertThat(checksumOn(remote, ticketChecksumSql(silo), orgId)).isEqualTo(ticketSumBefore);
            assertThat(checksumOn(remote, messageChecksumSql(silo), orgId)).isEqualTo(messageSumBefore);
            assertThat(regclassIsNull(remote, "tenant_pool.ticket"))
                    .describedAs("the destination holds no pool schema — there is nowhere for a"
                            + " misrouted pooled write to land silently")
                    .isTrue();
        }
        // The source copy is deliberately still on primary: the reverse stream keeps it current for
        // the whole watch window, and that copy is what makes rollback one row instead of a re-copy.
        assertThat(counts(silo, orgId)).isEqualTo(before);

        // API behaviour is identical from the caller's chair — same member, same grants, same first
        // page, and the cursor minted on PRIMARY still pages correctly against the REMOTE, because a
        // keyset cursor names row values and the rows came whole.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/tickets", orgId)
                        .param("page[size]", "1").with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(t3.toString()));
        mockMvc.perform(get("/api/v1/orgs/{orgId}/tickets", orgId)
                        .param("page[size]", "1").param("page[after]", cursorFromPrimary)
                        .with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(t2.toString()));

        // The pooled neighbour: rows exactly where they were, still served, from primary.
        assertThat(counts(TenantSchemas.TENANT_POOL, neighbourId)).isEqualTo(neighbourBefore);
        assertThat(TenantContext.callAsPlatform(() -> placements.find(neighbourId)).orElseThrow()
                .schemaName()).isEqualTo(TenantSchemas.TENANT_POOL);
        assertThat(TenantContext.callAs(neighbourId,
                () -> jdbc.queryForObject("select current_database()", String.class)))
                .isEqualTo(POSTGRES.getDatabaseName());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/tickets", neighbourId)
                        .param("page[size]", "1")
                        .with(token(neighbourSubject, neighbourExtOrg, neighbourAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(neighbourTicket.toString()));

        // ---- Phase 3: AGENTS §5.5 across the split. B is served from the second database (the 200
        // proves it), then loses their membership. The revocation is the production evictor's
        // guaranteed end state — membership row gone, org-permissions cleared — written directly so
        // the assertion does not race the async listener; and the assertion IS the very next request.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(subjectB, extOrgId, alias)))
                .andExpect(status().isOk());
        TenantContext.runAs(orgId, () -> jdbc.update(
                "delete from membership where org_id = ? and person_id = ?", orgId, personB));
        caches.getCache("org-permissions").clear();
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(subjectB, extOrgId, alias)))
                .andExpect(status().isForbidden());
        // The control that makes the denial attributable to the revocation, not to anything the move
        // broke: the unrevoked member's very next request still serves — asserted over HTTP and at
        // the port, so a resolver that quietly emptied for everyone could not pass as "revoked".
        assertThat(TenantContext.callAs(orgId, () -> orgAuthorization.permissions(personA, orgId)))
                .contains("member:read");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk());

        // ---- Phase 4: back again, by the same machinery with the roles swapped — a REAL reverse
        // cutover, not the cheap rollback (that path needs the forward row live; this one proves the
        // symmetric claim). Decommission first: it is refused while the watch window insures the
        // move, so wait the window out, then let it drop the stale primary copy and the streams.
        Thread.sleep(Duration.ofMillis(1500).toMillis());
        cutover.decommission(orgId);
        assertThat(TenantContext.callAsPlatform(() -> cutovers.find(orgId))).isEmpty();
        assertThat(regclassIsNullOnPrimary(silo + ".ticket"))
                .describedAs("decommission dropped the stale source copy — the tenant now exists on"
                        + " the second database ALONE")
                .isTrue();
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk());

        // What must come back is the tenant AS IT NOW IS — B's revocation happened after the forward
        // move, so "identical at both ends" is measured against the remote's current state.
        Map<String, Long> beforeReturn;
        try (Connection remote = TenantRemotes.remoteConnection()) {
            beforeReturn = countsOn(remote, silo, orgId);
        }
        CutoverLinks reverse = new CutoverLinks(
                bridgeConninfo(TenantRemotes.container()), bridgeConninfo(POSTGRES));
        CutoverReport back = cutover.moveToDatasource(orgId, TenantPlacement.PRIMARY_DATASOURCE, reverse);

        assertThat(back.freezeHeld())
                .isGreaterThanOrEqualTo(TenantRoutes.routeTtl())
                .isLessThan(Duration.ofSeconds(60));
        assertThat(TenantRoutes.routeOf(orgId))
                .isEqualTo(new TenantRoutes.Route(silo, TenantPlacement.PRIMARY_DATASOURCE));
        assertThat(TenantContext.callAs(orgId,
                () -> jdbc.queryForObject("select current_database()", String.class)))
                .isEqualTo(POSTGRES.getDatabaseName());
        assertThat(counts(silo, orgId)).isEqualTo(beforeReturn);
        assertThat(checksumOnPrimary(ticketChecksumSql(silo), orgId)).isEqualTo(ticketSumBefore);
        assertThat(checksumOnPrimary(messageChecksumSql(silo), orgId)).isEqualTo(messageSumBefore);

        // Same caller's-chair truths after the round trip: the member serves, the PRIMARY-minted
        // cursor still pages (its rows crossed two databases and kept their values), and the
        // revocation SURVIVED the journey — a move that resurrected B would be §5.5 broken by replay.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/tickets", orgId)
                        .param("page[size]", "1").with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(t3.toString()));
        mockMvc.perform(get("/api/v1/orgs/{orgId}/tickets", orgId)
                        .param("page[size]", "1").param("page[after]", cursorFromPrimary)
                        .with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(t2.toString()));
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(subjectB, extOrgId, alias)))
                .andExpect(status().isForbidden());

        // And close the loop: decommission the return move too, so the remote copy is provably
        // disposable and the tenant ends the journey exactly where it began, on one database.
        Thread.sleep(Duration.ofMillis(1500).toMillis());
        cutover.decommission(orgId);
        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(regclassIsNull(remote, silo + ".ticket")).isTrue();
        }
        assertThat(TenantContext.callAsPlatform(() -> placements.find(orgId)).orElseThrow())
                .satisfies(placement -> {
                    assertThat(placement.state()).isEqualTo(PlacementState.ACTIVE);
                    assertThat(placement.dataSourceName()).isEqualTo(TenantPlacement.PRIMARY_DATASOURCE);
                });
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(subjectA, extOrgId, alias)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ fixture and measurement

    /**
     * libpq conninfo for the far side of a subscription: the container's DEFAULT-BRIDGE address,
     * because the database server dialling it lives in another container where the host-mapped port
     * does not exist. See the class note.
     */
    private static String bridgeConninfo(PostgreSQLContainer container) {
        var networks = container.getContainerInfo().getNetworkSettings().getNetworks();
        String address = networks.values().iterator().next().getIpAddress();
        return "host=" + address + " port=5432 dbname=" + container.getDatabaseName()
                + " user=" + container.getUsername() + " password=" + container.getPassword();
    }

    private void grant(UUID org, UUID roleId, String... permissions) {
        TenantContext.runAs(org, () -> {
            for (String permission : permissions) {
                jdbc.update("insert into role_permission (role_id, permission) values (?, ?)",
                        roleId, permission);
            }
        });
    }

    private UUID ticket(String schema, UUID org, UUID opener, String subject, int minutesAgo) {
        UUID id = UUID.randomUUID();
        // Explicit, distinct created_at values: the ticket page sorts (createdAt desc, id desc), and
        // the cursor assertions need an order that cannot flip on insertion timing.
        jdbc.update("insert into " + q(schema) + ".ticket (id, org_id, opener_person_id, subject,"
                + " priority, status, first_response_due_at, resolution_due_at, escalated, version,"
                + " created_at) values (?, ?, ?, ?, 'P3', 'OPEN', now() + interval '1 hour',"
                + " now() + interval '1 day', false, 0, now() - make_interval(mins => ?))",
                id, org, opener, subject, minutesAgo);
        return id;
    }

    private void message(String schema, UUID ticketId, UUID author, String body) {
        jdbc.update("insert into " + q(schema) + ".ticket_message (id, ticket_id, author_person_id,"
                + " body, internal, created_at) values (?, ?, ?, ?, false, now())",
                UUID.randomUUID(), ticketId, author, body);
    }

    /** The OrgRbacApiTest token idiom: iss + linked subject + the PROVIDER's org id under its alias. */
    private static JwtRequestPostProcessor token(String subject, String externalOrgId, String alias) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(alias, Map.of("id", externalOrgId))));
    }

    /**
     * The tables this fixture seeds, counted with this test's own SQL against a NAMED schema — the
     * TenantPromotionTest discipline: asking {@code TenantRows} would be asking the machinery under
     * test whether it agrees with itself.
     */
    private static final List<String> FIXTURE_TABLES = List.of("org_role", "role_permission",
            "membership", "ticket", "ticket_message", "org_subscription", "audit_log");

    private Map<String, Long> counts(String schema, UUID org) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : FIXTURE_TABLES) {
            counts.put(table, TenantContext.callAsPlatform(() -> Optional.ofNullable(
                    jdbc.queryForObject(countSql(schema, table), Long.class, org)).orElse(0L)));
        }
        return counts;
    }

    private static Map<String, Long> countsOn(Connection side, String schema, UUID org)
            throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : FIXTURE_TABLES) {
            try (PreparedStatement statement = side.prepareStatement(countSql(schema, table))) {
                statement.setObject(1, org);
                try (ResultSet rows = statement.executeQuery()) {
                    counts.put(table, rows.next() ? rows.getLong(1) : 0L);
                }
            }
        }
        return counts;
    }

    private static String countSql(String schema, String table) {
        String qualified = q(schema);
        return switch (table) {
            // The children have no org_id of their own — they reach the org through their parent,
            // which is exactly the shape a WHERE org_id copy would silently drop.
            case "ticket_message" -> "select count(*) from " + qualified + ".ticket_message m join "
                    + qualified + ".ticket t on t.id = m.ticket_id where t.org_id = ?";
            case "role_permission" -> "select count(*) from " + qualified + ".role_permission p join "
                    + qualified + ".org_role r on r.id = p.role_id where r.org_id = ?";
            // The freeze itself writes maintenance.* audit rows on the org's axis (window scheduled /
            // cancelled), legitimately and on whichever side is serving at that instant — excluded so
            // the comparison is about the TENANT's rows, not the machinery's own bookkeeping.
            case "audit_log" -> "select count(*) from " + qualified + ".audit_log"
                    + " where org_id = ? and action not like 'maintenance.%'";
            default -> "select count(*) from " + qualified + "." + table + " where org_id = ?";
        };
    }

    /** Content, not counts: a row-value digest computed identically on both sides by THIS test. */
    private static String ticketChecksumSql(String schema) {
        return "select coalesce(md5(string_agg(id::text || '|' || subject || '|' || status, ','"
                + " order by id)), 'none') from " + q(schema) + ".ticket where org_id = ?";
    }

    private static String messageChecksumSql(String schema) {
        return "select coalesce(md5(string_agg(m.id::text || '|' || m.body, ',' order by m.id)),"
                + " 'none') from " + q(schema) + ".ticket_message m join " + q(schema)
                + ".ticket t on t.id = m.ticket_id where t.org_id = ?";
    }

    private String checksumOnPrimary(String sql, UUID org) {
        return TenantContext.callAsPlatform(() -> jdbc.queryForObject(sql, String.class, org));
    }

    private static String checksumOn(Connection side, String sql, UUID org) throws SQLException {
        try (PreparedStatement statement = side.prepareStatement(sql)) {
            statement.setObject(1, org);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static Map<String, Long> withoutAudit(Map<String, Long> counts) {
        Map<String, Long> narrowed = new LinkedHashMap<>(counts);
        narrowed.remove("audit_log");
        return narrowed;
    }

    private boolean regclassIsNullOnPrimary(String qualifiedTable) {
        return Boolean.TRUE.equals(TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select to_regclass(?) is null", Boolean.class, qualifiedTable)));
    }

    private static boolean regclassIsNull(Connection side, String qualifiedTable) throws SQLException {
        try (PreparedStatement statement = side.prepareStatement("select to_regclass(?) is null")) {
            statement.setString(1, qualifiedTable);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    /** Identifier quoting for the two schema shapes this test writes into. */
    private static String q(String schema) {
        if (TenantSchemas.TENANT_POOL.equals(schema)) {
            return TenantSchemas.TENANT_POOL;
        }
        return "\"" + TenantSchemas.requireSiloSchema(schema) + "\"";
    }

    // ------------------------------------------------------------------ replication cleanup

    /** DISABLE → slot_name=NONE → DROP: never dials the far side, so it cannot hang on a broken run. */
    private static void dropCutoverSubscriptions(Connection side) throws SQLException {
        for (String subscription : names(side,
                "select subname from pg_subscription where subname ~ '^s_t_[0-9a-f]{32}$'")) {
            try (Statement statement = side.createStatement()) {
                statement.execute("alter subscription \"" + subscription + "\" disable");
                statement.execute("alter subscription \"" + subscription + "\" set (slot_name = NONE)");
                statement.execute("drop subscription \"" + subscription + "\"");
            }
        }
    }

    private static void dropCutoverPublicationsAndSlots(Connection side) throws SQLException {
        for (String publication : names(side,
                "select pubname from pg_publication where pubname ~ '^p_t_[0-9a-f]{32}$'")) {
            try (Statement statement = side.createStatement()) {
                statement.execute("drop publication \"" + publication + "\"");
            }
        }
        try (Statement statement = side.createStatement()) {
            statement.execute("select pg_drop_replication_slot(slot_name) from pg_replication_slots"
                    + " where slot_name ~ '^s_t_[0-9a-f]{32}$' and not active");
        }
    }

    private static List<String> names(Connection side, String sql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = side.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }
}
