package ug.co.smsone.shared.tenancy.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.queue.QueueSignals;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * ADR 0010 §7 Phase 5's gate, as a test: <strong>a real organization with real data promoted to
 * {@code t_<32hex>} and demoted back, with per-table row counts identical at both ends</strong> — plus
 * the two properties the promotion is worthless without: that a neighbour sharing the pool does not
 * move, and that a background writer cannot get a write in while the freeze is up.
 *
 * <p><b>The counts here are hand-written, deliberately.</b> {@link TenantTierTables} derives the copy
 * plan from the catalogue and {@link TenantRows} fingerprints it, so verifying the move with either of
 * them would be asking the code under test whether it agreed with itself. Every assertion below counts
 * rows with SQL this test owns, naming both schemas explicitly, so a promoter that copied nothing and
 * reported success has nowhere to hide.
 *
 * <p><b>And they name their schemas rather than routing.</b> {@code TenantContext.runAs(orgId, …)}
 * resolves through the placement registry, which is exactly the row a promotion changes — so a test
 * that read through it would be unable to distinguish "the rows moved" from "the routing moved". The
 * question being asked here is a physical one: which schema holds the bytes.
 */
class TenantPromotionTest extends AbstractIntegrationTest {

    /**
     * Non-static: {@code TenantSilos} captures the Spring context in {@code beforeEach}. Registered for
     * its sweep rather than for {@code place()} — a promoted silo that survived this class would be
     * counted by every later fan-out and would fail {@code TenancyTierBoundaryTest} three files away.
     */
    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantPromoter promoter;

    @Autowired
    private TenantPlacements placements;

    @Autowired
    private TenantFreezes freezes;

    @Autowired
    private QueueSignals queueSignals;

    private UUID orgId;
    private UUID neighbourId;
    private UUID personId;

    @BeforeEach
    void seedTwoPooledTenants() {
        personId = EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
        orgId = pooledOrganization();
        neighbourId = pooledOrganization();
        seedTenantRows(orgId);
        seedTenantRows(neighbourId);
    }

    /**
     * <strong>Nothing this class froze may outlive it, and a failing test is exactly when that matters.</strong>
     *
     * <p>One Postgres container serves every test class, and {@link TenantSilos}' sweep only knows about
     * silo SCHEMAS — a {@code platform.tenant_freeze} row is invisible to it. These tenants are POOLED,
     * so a freeze left in force here makes {@code TenantFanOut.Fleet.poolPaused()} true for every class
     * that runs next: no retention purge, no SLA escalation, no trial expiry, and every pooled ticket a
     * 404 at the admin desk, for as long as the freeze has left to run. That is a cascade of failures in
     * files that name neither this class nor the test that leaked, and it has happened — a throw between
     * {@code freezes.freeze} and the {@code thaw} at the end of a test took six other classes down with
     * it. The thaw belongs here rather than at the end of a method precisely because the case it has to
     * cover is the method not reaching its end.
     *
     * <p>The placement rows go for the same reason one step further out: a PROVISIONING row naming
     * {@code tenant_pool}, which is what a promotion killed mid-copy leaves, stands the pool down for
     * {@code TenantFanOut.PROVISIONING_STANDS_DOWN_FOR} — two hours, which outlasts the whole suite.
     */
    @AfterEach
    void releaseAnythingThisTestLeftHoldingTheFleet() {
        for (UUID org : new UUID[] {orgId, neighbourId}) {
            if (org != null) {
                freezes.thaw(org);
                jdbc.update("delete from platform.tenant_placement where org_id = ?", org);
            }
        }
    }

    @Test
    void aRealOrgIsPromotedAndDemotedBackWithIdenticalPerTableCountsAtBothEnds() {
        String silo = TenantSchemas.siloSchema(orgId);
        Map<String, Long> beforeAnythingMoved = counts(TenantSchemas.TENANT_POOL, orgId);
        assertThat(beforeAnythingMoved.values()).describedAs("the fixture must actually seed rows, or"
                        + " every count below is trivially equal").allMatch(rows -> rows > 0);

        PromotionReport out = promoter.promote(orgId);

        assertThat(counts(silo, orgId))
                .describedAs("every table's rows must arrive in the silo")
                .isEqualTo(beforeAnythingMoved);
        assertThat(counts(TenantSchemas.TENANT_POOL, orgId))
                .describedAs("and must LEAVE the pool — a promotion that only copies leaves a ghost"
                        + " tenant the pooled fan-out keeps escalating, retrying and purging")
                .allSatisfy((table, rows) -> assertThat(rows).isZero());
        assertThat(reported(out)).describedAs("the promoter's own per-table verification must agree with"
                        + " this test's independent count of the same tables")
                .isEqualTo(withoutTheFreezesOwnRows(beforeAnythingMoved));
        assertThat(out.warnings()).describedAs("a clean promotion warns about nothing").isEmpty();

        PromotionReport back = promoter.demote(orgId);

        assertThat(counts(TenantSchemas.TENANT_POOL, orgId))
                .describedAs("ADR 0010 §6: hop 0->1 is reversible by copying back and flipping one row")
                .isEqualTo(beforeAnythingMoved);
        assertThat(counts(silo, orgId)).allSatisfy((table, rows) -> assertThat(rows).isZero());
        assertThat(reported(back)).isEqualTo(withoutTheFreezesOwnRows(beforeAnythingMoved));
        assertThat(back.warnings()).isEmpty();

        promoter.reclaim(silo);
        assertThat(schemaExists(silo)).describedAs("an emptied, unreferenced silo is reclaimable").isFalse();
    }

    /**
     * The child tables have no {@code org_id} at all — {@code ticket_message} reaches its organization
     * through {@code ticket}, {@code role_permission} through {@code org_role}. A copy that only
     * understood {@code where org_id = ?} would move the parents and silently leave every child behind,
     * and every assertion about "the tenant moved" would still pass.
     */
    @Test
    void childRowsReachTheirOrganizationThroughTheirParentAndTravelWithIt() {
        long messages = countTicketMessages(TenantSchemas.TENANT_POOL, orgId);
        long grants = countRolePermissions(TenantSchemas.TENANT_POOL, orgId);
        assertThat(messages).isPositive();
        assertThat(grants).isPositive();

        String silo = TenantSchemas.siloSchema(orgId);
        promoter.promote(orgId);

        assertThat(countTicketMessages(silo, orgId)).isEqualTo(messages);
        assertThat(countRolePermissions(silo, orgId)).isEqualTo(grants);
        assertThat(countTicketMessages(TenantSchemas.TENANT_POOL, orgId)).isZero();
        assertThat(countRolePermissions(TenantSchemas.TENANT_POOL, orgId)).isZero();
    }

    /** A promotion moves ONE tenant. Everybody else in the pool is untouched, rows and placement alike. */
    @Test
    void thePooledNeighbourDoesNotMove() {
        Map<String, Long> neighbourBefore = counts(TenantSchemas.TENANT_POOL, neighbourId);

        promoter.promote(orgId);

        assertThat(counts(TenantSchemas.TENANT_POOL, neighbourId)).isEqualTo(neighbourBefore);
        assertThat(counts(TenantSchemas.siloSchema(orgId), neighbourId))
                .describedAs("a neighbour's rows in a silo would be ADR 0010 §1's worst failure: a row"
                        + " whose org_id disagrees with the schema holding it")
                .allSatisfy((table, rows) -> assertThat(rows).isZero());
        assertThat(placementOf(neighbourId).schemaName()).isEqualTo(TenantSchemas.TENANT_POOL);
    }

    /**
     * <strong>The hazard ADR 0010 §6 calls out by name.</strong> The org-scoped {@code maintenance_window}
     * gates HTTP org paths only; every durable queue in this system claims its work outside any request,
     * so a promoter that took only the HTTP freeze would copy rows while a worker was still writing
     * them — and the verification would then pass or fail on timing.
     *
     * <p>This drives a real worker against the real claim path ({@code QueueSignals.claim}, which is how
     * all three queues find their next tenant) on another thread for the whole length of a real
     * promotion, and asserts two things at once: that the freeze was genuinely up while the copy ran,
     * and that the worker never once got the tenant while it was. The probe queue is a name of this
     * test's own so the loop can only ever claim signals this test raised.
     */
    @Test
    void aBackgroundWorkerCannotClaimTheTenantWhileTheFreezeIsUp() throws Exception {
        String probeQueue = "promotion-probe-" + UUID.randomUUID();
        queueSignals.raise(probeQueue, orgId);
        queueSignals.raise(probeQueue, neighbourId);
        assertThat(claimedScopes(probeQueue))
                .describedAs("before the freeze, a worker can reach both tenants")
                .containsExactlyInAnyOrder(orgId, neighbourId);
        // Put them back: claim() leases what it takes, and the point of the run below is what the
        // worker can reach, not what a previous claim happens to have parked.
        queueSignals.raise(probeQueue, orgId);
        queueSignals.raise(probeQueue, neighbourId);

        Set<UUID> claimedDuringTheFreeze = ConcurrentHashMap.newKeySet();
        AtomicBoolean sawTheFreeze = new AtomicBoolean();
        AtomicBoolean sawProvisioning = new AtomicBoolean();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            while (running.get()) {
                boolean frozen = freezes.isFrozen(orgId);
                sawTheFreeze.compareAndSet(false, frozen);
                sawProvisioning.compareAndSet(false,
                        placements.find(orgId).map(p -> p.state() == PlacementState.PROVISIONING)
                                .orElse(false));
                if (frozen) {
                    // Ask the same question the notification, webhook and exchange workers ask, at the
                    // same statement, while the copy is in flight.
                    //
                    // RE-CHECKED ON THE FAR SIDE, and the assertion is worthless without it. The read
                    // above and this claim are two statements, and the promoter lifts the freeze as its
                    // LAST act while this probe keeps running until promote() returns — so the final
                    // iterations straddle the thaw by construction. A sample taken across it recorded a
                    // claim that was legitimate at the moment it was made and then failed the assertion
                    // for it. Under the full suite's contention that window is wide enough to hit, which
                    // is why this passed alone and failed in the suite.
                    //
                    // Only samples where the freeze was demonstrably up on BOTH sides of the claim are
                    // evidence about what a freeze blocks. This narrows what is observed, never what is
                    // asserted: every surviving sample must still not name the frozen tenant.
                    queueSignals.claim(probeQueue, Duration.ofMillis(200)).ifPresent(leased -> {
                        if (freezes.isFrozen(orgId)) {
                            claimedDuringTheFreeze.add(leased.scope());
                        } else {
                            // Taken across the thaw: put it back, or the neighbour assertion below can
                            // starve on a lease this probe parked after the run it was measuring.
                            queueSignals.raise(probeQueue, leased.scope());
                        }
                    });
                }
                try {
                    // The test pool holds three connections and Flyway takes two of them while the silo
                    // is being migrated. A tight loop here would spend the promotion queueing for the
                    // third; ten milliseconds still gives hundreds of probes across the settle wait.
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "promotion-freeze-probe");
        worker.setDaemon(true);
        worker.start();

        try {
            promoter.promote(orgId);
        } finally {
            running.set(false);
            worker.join(Duration.ofSeconds(30).toMillis());
        }

        assertThat(sawTheFreeze).describedAs("the probe must have observed the freeze, or it proves"
                        + " nothing about what the freeze blocks").isTrue();
        assertThat(sawProvisioning).describedAs("the placement must read PROVISIONING for the length of"
                        + " the move — that is what TenantFanOut reads to stand its sweeps down").isTrue();
        assertThat(claimedDuringTheFreeze)
                .describedAs("QueueSignals.claim must never hand out a tenant whose rows are being"
                        + " copied; the neighbour stays claimable throughout, which is what proves the"
                        + " pause is scoped to one tenant and not to the queue (ADR 0010 §6 hop 0->1)")
                .doesNotContain(orgId);
        assertThat(claimedDuringTheFreeze).contains(neighbourId);

        assertThat(freezes.isFrozen(orgId)).describedAs("and the freeze comes off").isFalse();
        assertThat(placementOf(orgId).state()).isEqualTo(PlacementState.ACTIVE);
        assertThat(claimedScopes(probeQueue)).contains(orgId);
    }

    /** The freeze row is the pause, and {@link TenantFreezes#requireWritable} is how a job asks. */
    @Test
    void aFrozenTenantRefusesTheGuardAndAnExpiredFreezeDoesNot() {
        assertThat(freezes.isFrozen(orgId)).isFalse();
        freezes.freeze(orgId, "unit probe", "test", Duration.ofMinutes(5));

        assertThat(freezes.isFrozen(orgId)).isTrue();
        assertThatThrownBy(() -> freezes.requireWritable(orgId))
                .isInstanceOf(TenantFrozenException.class)
                .hasMessageContaining(orgId.toString());
        assertThat(freezes.inForce()).extracting(TenantFreezes.Freeze::orgId).contains(orgId);

        // An expired row is NOT a freeze — the property that stops a killed promoter pausing a tenant's
        // background work forever (V58's expires_at). Written by hand because waiting one out is not a
        // test, it is a delay.
        //
        // BOTH ENDS OF THE WINDOW MOVE. V58's `tenant_freeze_ends_after_it_starts` refuses a row whose
        // expires_at precedes its frozen_at, and that constraint is load-bearing: such a row reads as
        // "not frozen" to every consumer, so a promotion would copy rows with nothing paused. Dragging
        // only expires_at backwards asks for a state a real freeze can never be in; what a promoter
        // killed ten minutes ago on a five-minute budget leaves behind is this.
        jdbc.update("""
                update platform.tenant_freeze
                   set frozen_at = now() - interval '10 minutes',
                       expires_at = now() - interval '5 minutes'
                 where org_id = ?
                """, orgId);
        assertThat(freezes.isFrozen(orgId)).isFalse();
        assertThat(freezes.inForce()).extracting(TenantFreezes.Freeze::orgId).doesNotContain(orgId);
        freezes.thaw(orgId);
    }

    @Test
    void aTenantThatIsAlreadySiloedCannotBePromotedAgainAndOneThatIsPooledCannotBeDemoted() {
        assertThatThrownBy(() -> promoter.demote(orgId))
                .isInstanceOf(TenantPromotionException.class)
                .hasMessageContaining("already pooled");

        promoter.promote(orgId);

        assertThatThrownBy(() -> promoter.promote(orgId))
                .isInstanceOf(TenantPromotionException.class)
                .hasMessageContaining("already serves from its own schema");
    }

    /**
     * A silo that still holds rows is either a demotion whose delete did not finish or a misrouted
     * write — ADR 0010 §1's worst failure — and dropping it would destroy the only evidence.
     */
    @Test
    void reclaimRefusesASchemaThatIsStillReferencedOrStillHoldsRows() {
        String silo = TenantSchemas.siloSchema(orgId);
        promoter.promote(orgId);

        assertThatThrownBy(() -> promoter.reclaim(silo))
                .isInstanceOf(TenantPromotionException.class)
                .hasMessageContaining("still named by a row in platform.tenant_placement");

        promoter.demote(orgId);
        jdbc.update("insert into " + quoted(silo) + ".audit_log"
                + " (id, org_id, action, occurred_at, version, created_at)"
                + " values (?, ?, 'probe.left_behind', now(), 0, now())", UUID.randomUUID(), orgId);

        assertThatThrownBy(() -> promoter.reclaim(silo))
                .isInstanceOf(TenantPromotionException.class)
                .hasMessageContaining("misrouted write");
    }

    /**
     * A tenant with no placement row is not a tenant this can move: the flip is a compare-and-swap on a
     * row that has to be there, and writing one here would announce a tenant nobody registered.
     */
    @Test
    void aTenantWithNoPlacementRowIsNotSomethingThisCanMove() {
        UUID unplaced = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());

        assertThatThrownBy(() -> promoter.promote(unplaced))
                .isInstanceOf(TenantPromotionException.class)
                .hasMessageContaining("no row in platform.tenant_placement");
    }

    // ---------------------------------------------------------------- fixture

    /**
     * An organization plus the ACTIVE {@code tenant_pool} placement a real signup writes in the same
     * transaction as the tenant ({@code OrganizationService.create} → {@code TenantPlacements.announce}).
     * {@code EdgeSeed.organization} predates the registry and writes only the platform-tier rows, so
     * without this the promoter would — correctly — refuse a tenant with no recorded home.
     */
    private UUID pooledOrganization() {
        UUID id = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());
        TenantContext.runAsPlatform(() -> placements.announce(id, TenantSchemas.TENANT_POOL));
        return id;
    }

    /**
     * Rows across the three shapes the copy has to get right: tables discriminated by {@code org_id}
     * directly, children that reach their organization through a parent, and the tenant half of a split
     * table ({@code audit_log}).
     *
     * <p>Written straight into {@code tenant_pool} by name rather than through the tenant axis, so the
     * fixture states where the bytes are instead of asking the router where they would go.
     */
    private void seedTenantRows(UUID org) {
        UUID roleId = EdgeSeed.member(jdbc, org, personId, "ADMIN");
        String pool = quoted(TenantSchemas.TENANT_POOL);
        jdbc.update("insert into " + pool + ".role_permission (role_id, permission) values (?, 'org:read')",
                roleId);
        jdbc.update("insert into " + pool + ".role_permission (role_id, permission) values (?, 'org:write')",
                roleId);

        UUID firstTicket = UUID.randomUUID();
        UUID secondTicket = UUID.randomUUID();
        for (UUID ticketId : new UUID[] {firstTicket, secondTicket}) {
            jdbc.update("insert into " + pool + ".ticket (id, org_id, opener_person_id, subject, priority,"
                    + " status, first_response_due_at, resolution_due_at, escalated, version, created_at)"
                    + " values (?, ?, ?, ?, 'P3', 'OPEN', now() + interval '1 hour',"
                    + " now() + interval '1 day', false, 0, now())",
                    ticketId, org, personId, "ticket " + ticketId);
        }
        jdbc.update("insert into " + pool + ".ticket_message (id, ticket_id, author_person_id, body,"
                + " internal, created_at) values (?, ?, ?, 'first', false, now())",
                UUID.randomUUID(), firstTicket, personId);
        jdbc.update("insert into " + pool + ".ticket_message (id, ticket_id, author_person_id, body,"
                + " internal, created_at) values (?, ?, ?, 'second', true, now())",
                UUID.randomUUID(), firstTicket, personId);
        jdbc.update("insert into " + pool + ".ticket_message (id, ticket_id, author_person_id, body,"
                + " internal, created_at) values (?, ?, ?, 'third', false, now())",
                UUID.randomUUID(), secondTicket, personId);

        UUID subscriptionId = UUID.randomUUID();
        jdbc.update("insert into " + pool + ".webhook_subscription (id, org_id, url, secret, event_types,"
                + " status, version, created_at)"
                + " values (?, ?, 'https://example.test/hook', 'sh-secret', 'ticket.opened', 'ACTIVE',"
                + " 0, now())", subscriptionId, org);
        for (int attempt = 0; attempt < 2; attempt++) {
            jdbc.update("insert into " + pool + ".webhook_delivery (id, subscription_id, org_id,"
                    + " event_type, payload, status, attempts, max_attempts, next_attempt_at, created_at)"
                    + " values (?, ?, ?, 'ticket.opened', '{}', 'PENDING', 0, 5, now(), now())",
                    UUID.randomUUID(), subscriptionId, org);
        }

        for (String action : new String[] {"organization.created", "member.added"}) {
            jdbc.update("insert into " + pool + ".audit_log (id, org_id, action, occurred_at, version,"
                    + " created_at) values (?, ?, ?, now(), 0, now())", UUID.randomUUID(), org, action);
        }
    }

    /**
     * The tables this fixture seeds, in the order the assertions read them. Not the whole tenant: the
     * promotion moves twenty-seven tables and this test proves the move on the eight that exercise the
     * three shapes that can go wrong. {@code TenantTierTablesPlanTest} is what proves the other
     * nineteen are not silently skipped.
     */
    private static final String AUDIT_LOG = "audit_log";

    private static final java.util.List<String> FIXTURE_TABLES = java.util.List.of(
            "org_role", "role_permission", "membership", "ticket", "ticket_message",
            "webhook_subscription", "webhook_delivery", "audit_log");

    /** One organization's rows per table, counted with this test's own SQL against a NAMED schema. */
    private Map<String, Long> counts(String schema, UUID org) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : FIXTURE_TABLES) {
            counts.put(table, switch (table) {
                case "role_permission" -> countRolePermissions(schema, org);
                case "ticket_message" -> countTicketMessages(schema, org);
                // The freeze writes its own `maintenance.window_scheduled` row into the tenant before
                // the copy begins, so the audit table legitimately gains a row that no assertion about
                // the FIXTURE's data should have to know about. Excluding the promoter's own
                // bookkeeping is what keeps this comparison about the tenant.
                case AUDIT_LOG -> count("select count(*) from " + quoted(schema) + ".audit_log"
                        + " where org_id = ? and action not like 'maintenance.%'", org);
                default -> count("select count(*) from " + quoted(schema) + "." + table
                        + " where org_id = ?", org);
            });
        }
        return counts;
    }

    private long countTicketMessages(String schema, UUID org) {
        return count("select count(*) from " + quoted(schema) + ".ticket_message m"
                + " join " + quoted(schema) + ".ticket t on t.id = m.ticket_id where t.org_id = ?", org);
    }

    private long countRolePermissions(String schema, UUID org) {
        return count("select count(*) from " + quoted(schema) + ".role_permission p"
                + " join " + quoted(schema) + ".org_role r on r.id = p.role_id where r.org_id = ?", org);
    }

    /**
     * The promoter's own verification, narrowed to the tables this test counts by hand.
     *
     * <p>It covers every tenant-tier table there is — twenty-seven of them — and the freeze itself adds
     * rows to two of those ({@code maintenance_window} and the {@code maintenance.*} audit entries the
     * window's scheduling writes), which is why the comparison is per table over the fixture's own set
     * rather than over a grand total.
     */
    private static Map<String, Long> reported(PromotionReport report) {
        Map<String, Long> narrowed = new LinkedHashMap<>();
        for (String table : FIXTURE_TABLES) {
            if (!AUDIT_LOG.equals(table)) {
                narrowed.put(table, report.verified().get(table).rows());
            }
        }
        return narrowed;
    }

    /** The fixture's counts, minus the one table the promoter's own freeze writes into. */
    private static Map<String, Long> withoutTheFreezesOwnRows(Map<String, Long> counts) {
        Map<String, Long> narrowed = new LinkedHashMap<>(counts);
        narrowed.remove(AUDIT_LOG);
        return narrowed;
    }

    private long count(String sql, Object... args) {
        return TenantContext.callAsPlatform(
                () -> Optional.ofNullable(jdbc.queryForObject(sql, Long.class, args)).orElse(0L));
    }

    private TenantPlacement placementOf(UUID org) {
        return TenantContext.callAsPlatform(() -> placements.find(org)).orElseThrow();
    }

    /**
     * Every tenant this queue will hand out right now. Bounded, because {@code claim} parks what it
     * takes for only as long as the lease and an unbounded loop would eventually be claiming the same
     * tenants back — a hang rather than a failure, which is the worst way for a test to be wrong.
     */
    private Set<UUID> claimedScopes(String queue) {
        Set<UUID> scopes = new java.util.LinkedHashSet<>();
        for (int attempt = 0; attempt < 8; attempt++) {
            Optional<QueueSignals.Leased> leased = queueSignals.claim(queue, Duration.ofSeconds(5));
            if (leased.isEmpty()) {
                break;
            }
            scopes.add(leased.get().scope());
        }
        return scopes;
    }

    private boolean schemaExists(String schema) {
        return count("select count(*) from information_schema.schemata where schema_name = ?", schema) > 0;
    }

    /** Identifier quoting, so a {@code t_<hex>} schema name is safe in the literal SQL above. */
    private static String quoted(String schema) {
        return "\"" + TenantSchemaNames.require(schema) + "\"";
    }
}
