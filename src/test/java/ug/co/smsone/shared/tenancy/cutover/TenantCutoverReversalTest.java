package ug.co.smsone.shared.tenancy.cutover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.cutover.TenantCutovers.Cutover;
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
 * <strong>Everything a cutover does that is NOT the forward-and-back journey</strong> —
 * {@link TenantCutover#rollBack}, {@link TenantCutover#abort},
 * {@link TenantCutover#reclaimAbandonedCopy}, {@link TenantCutover#reclaimAbandonedSlots}, the resume
 * branch, and the refusals — against the same two real databases
 * {@link ug.co.smsone.shared.tenancy.cutover.TenantDatabaseCutoverTest} uses.
 *
 * <p>It exists because that class certifies exactly one path: forward, then a fresh reverse cutover.
 * Every operation an incident actually reaches for was unexercised, and three of them are the ones that
 * can leave a <em>replication slot pinning WAL until {@code max_slot_wal_keep_size} invalidates it</em>
 * (ADR 0011 §7.2 step 10). A gate that only proves the happy path is a gate for the day nothing goes
 * wrong.
 *
 * <h2>The two claims worth stating before the code</h2>
 *
 * <ul>
 *   <li><strong>Rollback is lossless because of step 9's ordering, and that is asserted as a row.</strong>
 *       {@link #aResumedSyncFlipsAndTheReverseStreamIsWhatMakesRollbackLossless} writes a ticket
 *       <em>on the destination, after the flip</em>, through the org's own axis — so the write exists on
 *       the second database and nowhere else unless the reverse stream carried it home. Rolling back and
 *       finding that row on primary is the whole of "no destination write exists before the reverse
 *       stream does"; a rollback test that only counted pre-move rows would pass with the reverse stream
 *       never created.</li>
 *   <li><strong>An orphaned slot is produced for real, not simulated.</strong>
 *       {@link #theEscapeOrphansASlotAndOnlyTheReclaimCanRemoveIt} makes the publisher genuinely
 *       unreachable to a live subscription ({@code ALTER SUBSCRIPTION … CONNECTION} at a refused port),
 *       which is the one failure ADR 0011 §7.2 measured the {@code DISABLE → slot_name=NONE → DROP}
 *       escape against. Measured again here before this class was written, against two scratch 18.4
 *       containers: the plain drop fails {@code 08006 … could not connect to publisher when attempting
 *       to drop replication slot}, the escape succeeds, and the slot survives on the publisher
 *       {@code active=f, wal_status=reserved} — the state that has no cure but a drop.</li>
 * </ul>
 *
 * <p>Where a precondition is a half-finished cutover, it is built with the <em>shipped</em> statements
 * ({@link CutoverDestinations#build}, {@link TenantReplication#createPublication},
 * {@link TenantReplication#createSubscription}, {@link TenantCutovers#begin}) rather than hand-written
 * SQL — this test lives in the package on purpose, so "what a crashed run leaves behind" is spelled the
 * way the crashed run spelled it, and cannot drift from it.
 */
class TenantCutoverReversalTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @RegisterExtension
    final TenantRemotes remotes = new TenantRemotes();

    @DynamicPropertySource
    static void cutoverTuning(DynamicPropertyRegistry registry) {
        TenantRemotes.register(registry);
        // The opposite choice to the gate test's PT1S, and the reason is that the refusal is the
        // subject here: decommission must be REFUSED while the window insures the move, and a window
        // that elapses inside a test method cannot prove a refusal. Nothing in this class ever waits
        // for it — the reversal under test is rollBack, which is what the window exists to keep cheap.
        registry.add("app.tenancy.cutover.watch-window", () -> "PT10M");
    }

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
    private CutoverPools pools;

    @Autowired
    private CutoverDestinations destinations;

    /** Every organization this class touched, so the cleanup can name them after a failure anywhere. */
    private final List<UUID> touched = new ArrayList<>();

    /**
     * The gate test's cleanup argument verbatim, and it matters more here: several methods below
     * deliberately END with a slot or a stream in place until the assertion that removes it, so a
     * failure in the middle leaves replication objects on containers shared by every context in the
     * JVM. Escape-hatch first (DISABLE → slot_name=NONE → DROP never dials the far side, so it cannot
     * hang on whatever broke the test), then the orphaned slots on both sides, then the rows.
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
        for (UUID org : touched) {
            freezes.thaw(org);
            jdbc.update("delete from platform.tenant_cutover where org_id = ?", org);
            jdbc.update("delete from platform.tenant_placement where org_id = ?", org);
            TenantRoutes.forget(org);
        }
    }

    // ---------------------------------------------------------------- the reversal that must be cheap

    /**
     * <strong>Two claims in one journey, because the second needs the first's outcome.</strong>
     *
     * <p><b>The resume branch</b> (§7.2 step 3's failure story: "re-running {@link
     * TenantCutover#moveToDatasource} resumes the wait"). A SYNCING row with live streams is what a
     * process killed during the online phase leaves, and the retry must adopt it rather than try to
     * begin a second cutover of the same tenant. Asserted on {@code started_at}: a fresh
     * {@link TenantCutovers#begin} stamps {@code now()}, so the row still carrying the instant this
     * test wrote is the only evidence that the move RESUMED rather than restarted — and it is evidence
     * a re-begin could not fake.
     *
     * <p><b>The rollback guarantee</b>, as a row rather than as a sentence. After the flip the tenant
     * is live on the second database; the ticket written here goes through the org's own axis, so it
     * lands there and NOWHERE else — the reverse stream established inside the freeze (step 9) is the
     * only thing that can put it back on primary. {@link TenantCutover#rollBack} then flips back, and
     * the assertion that the row is on primary <em>schema-qualified, on the platform axis</em> is the
     * class's central claim measured instead of asserted in a javadoc.
     */
    @Test
    void aResumedSyncFlipsAndTheReverseStreamIsWhatMakesRollbackLossless() throws Exception {
        UUID orgId = organization();
        String silo = silos.place(orgId);
        UUID person = EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
        EdgeSeed.member(jdbc, orgId, person, "ROLLBACK_GATE");
        UUID beforeTheMove = ticketOnPrimary(silo, orgId, person, "written before the move");
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, orgId, "ticket", "id", beforeTheMove);

        CutoverLinks links = forwardLinks();
        // Steps 1-3, by the shipped statements: the state a run killed mid-sync leaves behind.
        assertThat(TenantContext.callAsPlatform(() ->
                cutovers.begin(orgId, silo, TenantPlacement.PRIMARY_DATASOURCE, TenantRemotes.DATASOURCE)))
                .isTrue();
        destinations.build(pools.poolFor(TenantRemotes.DATASOURCE), silo, false);
        primarySide().createPublication(silo);
        remoteSide().createSubscription(silo, links.sourceConninfo(),
                TenantReplication.publicationName(silo), true);
        Instant startedAt = cutoverRow(orgId).startedAt();

        CutoverReport out = cutover.moveToDatasource(orgId, TenantRemotes.DATASOURCE, links);

        assertThat(out.warnings()).isEmpty();
        Cutover watching = cutoverRow(orgId);
        assertThat(watching.state()).isEqualTo(State.WATCHING);
        assertThat(watching.startedAt())
                .describedAs("the move ADOPTED the SYNCING row it found — a second begin() would have"
                        + " stamped a new started_at, and a second destination build would have refused"
                        + " the already-populated tier")
                .isEqualTo(startedAt);

        // The write that exists on the destination and nowhere else. Unqualified, on the org's axis:
        // if routing were wrong this would land on primary and the assertion after the rollback would
        // pass for the wrong reason, which is why the current_database() check brackets it.
        assertThat(TenantContext.callAs(orgId,
                () -> jdbc.queryForObject("select current_database()", String.class)))
                .isEqualTo("remotedb");
        UUID afterTheFlip = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update("insert into ticket (id, org_id,"
                + " opener_person_id, subject, priority, status, first_response_due_at,"
                + " resolution_due_at, escalated, version, created_at) values (?, ?, ?, ?, 'P3',"
                + " 'OPEN', now() + interval '1 hour', now() + interval '1 day', false, 0, now())",
                afterTheFlip, orgId, person, "written on the destination, after the flip"));
        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(countOn(remote, silo, "ticket", afterTheFlip))
                    .describedAs("the write is PHYSICALLY on the second database — asked over a direct"
                            + " connection, because asking through the router would be asking the thing"
                            + " under test whether it agrees with itself")
                    .isEqualTo(1L);
        }

        CutoverReport back = cutover.rollBack(orgId);

        assertThat(back.fromDatasource()).isEqualTo(TenantRemotes.DATASOURCE);
        assertThat(back.toDatasource()).isEqualTo(TenantPlacement.PRIMARY_DATASOURCE);
        assertThat(back.freezeHeld())
                .describedAs("the flip-back holds one route TTL like every other flip, and no longer")
                .isGreaterThanOrEqualTo(TenantRoutes.routeTtl())
                .isLessThan(Duration.ofSeconds(60));
        assertThat(TenantRoutes.routeOf(orgId))
                .isEqualTo(new TenantRoutes.Route(silo, TenantPlacement.PRIMARY_DATASOURCE));
        assertThat(TenantContext.callAs(orgId,
                () -> jdbc.queryForObject("select current_database()", String.class)))
                .isEqualTo(POSTGRES.getDatabaseName());

        assertThat(countOnPrimary(silo, "ticket", afterTheFlip))
                .describedAs("THE rollback guarantee: a write the destination served came home, because"
                        + " the reverse stream existed before the destination could take it (§7.2 step 9)")
                .isEqualTo(1L);
        assertThat(countOnPrimary(silo, "ticket", beforeTheMove))
                .describedAs("and nothing that was always here was lost on the way out and back")
                .isEqualTo(1L);
        assertThat(TenantContext.callAsPlatform(() -> cutovers.find(orgId)))
                .describedAs("a rolled-back cutover is over: the row is deleted, not marked")
                .isEmpty();
        assertThat(freezes.isFrozen(orgId)).isFalse();

        // The stale copy is deliberately left, and the report — not a log line — is what says so.
        assertThat(back.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("reclaimAbandonedCopy"));
        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(regclassIsNull(remote, silo + ".ticket"))
                    .describedAs("evidence, if the rollback was prompted by a data defect")
                    .isFalse();
        }
        assertThatThrownBy(() ->
                cutover.reclaimAbandonedCopy(orgId, TenantPlacement.PRIMARY_DATASOURCE))
                .isInstanceOf(TenantCutoverException.class)
                .hasMessageContaining("is SERVED from");

        cutover.reclaimAbandonedCopy(orgId, TenantRemotes.DATASOURCE);

        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(regclassIsNull(remote, silo + ".ticket")).isTrue();
        }
        assertThat(cutover.reportAbandonedSlots(TenantPlacement.PRIMARY_DATASOURCE))
                .describedAs("a whole forward-and-back cycle leaves no WAL pinned anywhere")
                .doesNotContain(TenantReplication.subscriptionName(silo));
        assertThat(cutover.reportAbandonedSlots(TenantRemotes.DATASOURCE))
                .doesNotContain(TenantReplication.subscriptionName(silo));
    }

    // ------------------------------------------------------------------------------ tearing one down

    /**
     * {@link TenantCutover#abort} on the state it is for: SYNCING, streams live, nothing flipped. The
     * assertion that matters is not that it returned an empty list but that <strong>no slot survives
     * it</strong> — an abort that removes the cutover row while leaving a slot behind removes the only
     * record that the slot exists, which is exactly how one becomes invisible.
     *
     * <p>The half-built destination copy is left on purpose (destroying bytes is a runbook step with a
     * human in it), so the abort's own log line points at {@link TenantCutover#reclaimAbandonedCopy} —
     * exercised here as the disposal it claims to be.
     */
    @Test
    void abortTearsDownASyncingCutoverAndLeavesNoSlotPinningWal() throws Exception {
        UUID orgId = organization();
        String silo = silos.place(orgId);
        String slot = TenantReplication.subscriptionName(silo);
        syncingCutover(orgId, silo);

        assertThat(slotsOnPrimary()).contains(slot);
        assertThat(remoteSide().subscriptionExists(silo)).isTrue();
        assertThat(primarySide().publicationExists(silo)).isTrue();

        List<String> leftovers = cutover.abort(orgId);

        assertThat(leftovers)
                .describedAs("empty is the only clean answer — every entry is a human's to-do")
                .isEmpty();
        assertThat(primarySide().publicationExists(silo)).isFalse();
        assertThat(remoteSide().subscriptionExists(silo)).isFalse();
        assertThat(slotsOnPrimary())
                .describedAs("the row is gone, so a surviving slot would be a WAL leak nothing records")
                .doesNotContain(slot);
        assertThat(TenantContext.callAsPlatform(() -> cutovers.find(orgId))).isEmpty();
        assertThat(TenantContext.callAsPlatform(() -> placements.find(orgId)).orElseThrow())
                .satisfies(placement -> {
                    assertThat(placement.state()).isEqualTo(PlacementState.ACTIVE);
                    assertThat(placement.dataSourceName()).isEqualTo(TenantPlacement.PRIMARY_DATASOURCE);
                });
        assertThat(freezes.isFrozen(orgId)).isFalse();

        // Aborting twice is a re-run, not a second incident.
        assertThatThrownBy(() -> cutover.abort(orgId))
                .isInstanceOf(TenantCutoverException.class)
                .hasMessageContaining("no cutover is recorded");

        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(regclassIsNull(remote, silo + ".ticket"))
                    .describedAs("the half-build survives the abort — bytes are a human's call")
                    .isFalse();
        }
        cutover.reclaimAbandonedCopy(orgId, TenantRemotes.DATASOURCE);
        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(regclassIsNull(remote, silo + ".ticket")).isTrue();
        }
    }

    /**
     * The row-without-streams state — a build that died between {@link TenantCutovers#begin} and
     * {@code CREATE SUBSCRIPTION}. A resume must not silently continue from it (there is no stream to
     * wait for, so the drain would hang until the online budget expired), and the refusal names the
     * one command that clears it.
     */
    @Test
    void aSyncingRowWhoseStreamsWereNeverBuiltRefusesToResumeAndAbortClearsIt() {
        UUID orgId = organization();
        String silo = silos.place(orgId);
        assertThat(TenantContext.callAsPlatform(() ->
                cutovers.begin(orgId, silo, TenantPlacement.PRIMARY_DATASOURCE, TenantRemotes.DATASOURCE)))
                .isTrue();

        assertThatThrownBy(() ->
                cutover.moveToDatasource(orgId, TenantRemotes.DATASOURCE, forwardLinks()))
                .isInstanceOf(TenantCutoverException.class)
                .hasMessageContaining("its replication objects do not")
                .hasMessageContaining("abort()");

        assertThat(cutover.abort(orgId)).isEmpty();
        assertThat(TenantContext.callAsPlatform(() -> cutovers.find(orgId))).isEmpty();
    }

    // -------------------------------------------------------------------------- the slot that orphans

    /**
     * <strong>The WAL-pinning slot, produced for real and then removed by the only shipped call that
     * can remove it.</strong>
     *
     * <p>The escape in {@link TenantReplication#dropSubscriptionAndSlot} is bought with an orphaned
     * slot, and it is worth that price for exactly one failure: a publisher that genuinely cannot be
     * asked. {@code ALTER SUBSCRIPTION … CONNECTION} at a refused port is that failure, reproducibly —
     * measured on 18.4 before this test existed:
     *
     * <pre>
     * ERROR:  08006: could not connect to publisher when attempting to drop replication slot "s_t_…":
     *         connection to server at "127.0.0.1", port 1 failed: Connection refused
     * HINT:   Use ALTER SUBSCRIPTION ... DISABLE …, and then … SET (slot_name = NONE) …
     * </pre>
     *
     * <p>Then the three claims the reclaim rests on, in order: the slot SURVIVES the escape (that is
     * the leak); a live cutover row EXPLAINS it, so the sweep must not touch it (that is what makes
     * the sweep safe to run against a database a tenant is being served from); and with no row to
     * explain it, {@link TenantCutover#reclaimAbandonedSlots} drops it (that is the part that did not
     * exist, and without which every one of these ended at a log line and a human with psql).
     */
    @Test
    void theEscapeOrphansASlotAndOnlyTheReclaimCanRemoveIt() {
        UUID orgId = organization();
        String silo = silos.place(orgId);
        String slot = TenantReplication.subscriptionName(silo);
        bareStreams(silo);

        assertThat(slotsOnPrimary()).contains(slot);
        assertThat(cutover.reportAbandonedSlots(TenantPlacement.PRIMARY_DATASOURCE))
                .describedAs("an attached slot is `active` and is never abandoned — Postgres will not"
                        + " even let it be dropped (55006), which is the sweep's second safety net")
                .doesNotContain(slot);

        // The publisher, made genuinely unreachable to this subscription and to nothing else.
        remoteJdbc().execute("alter subscription " + quoted(slot)
                + " connection 'host=127.0.0.1 port=1 dbname=nowhere user=nobody password=none'");

        Optional<String> orphan = remoteSide().dropSubscriptionAndSlot(silo, "datasource 'primary'");

        assertThat(orphan)
                .describedAs("the escape was taken, and the caller is TOLD what it cost — a return"
                        + " value, because a log line is not where the runbook sends the operator")
                .contains(slot);
        assertThat(remoteSide().subscriptionExists(silo)).isFalse();
        awaitSlotInactive(slot);
        assertThat(slotsOnPrimary())
                .describedAs("this is the leak: no subscription anywhere refers to it, and it pins WAL"
                        + " on primary until max_slot_wal_keep_size invalidates it")
                .contains(slot);

        assertThat(TenantContext.callAsPlatform(() ->
                cutovers.begin(orgId, silo, TenantPlacement.PRIMARY_DATASOURCE, TenantRemotes.DATASOURCE)))
                .isTrue();
        assertThat(cutover.reportAbandonedSlots(TenantPlacement.PRIMARY_DATASOURCE))
                .describedAs("an in-flight cutover explains its own slot, whatever its activity — a"
                        + " WATCHING tenant's reverse stream must be out of reach even during the"
                        + " seconds its apply worker spends reconnecting")
                .doesNotContain(slot);
        assertThat(cutover.reclaimAbandonedSlots(TenantPlacement.PRIMARY_DATASOURCE))
                .doesNotContain(slot);
        assertThat(slotsOnPrimary()).contains(slot);

        TenantContext.runAsPlatform(() -> cutovers.end(orgId));

        assertThat(cutover.reportAbandonedSlots(TenantPlacement.PRIMARY_DATASOURCE)).contains(slot);
        assertThat(cutover.reclaimAbandonedSlots(TenantPlacement.PRIMARY_DATASOURCE)).contains(slot);
        assertThat(slotsOnPrimary()).doesNotContain(slot);
        assertThat(cutover.reportAbandonedSlots(TenantPlacement.PRIMARY_DATASOURCE))
                .doesNotContain(slot);
    }

    /**
     * The other half of the narrowing, and the reason the blanket {@code catch (RuntimeException)} was
     * a bug rather than a style: a failure the escape does not repair must be RETHROWN with the slot
     * still attached, because an attached slot is a state a retry can finish cleanly and an orphaned
     * one is a state only a human can.
     *
     * <p>The non-escapable shape used here is the measured one — {@code 25001: DROP SUBSCRIPTION
     * cannot run inside a transaction block} — driven by putting the remote side's own template in a
     * transaction of its own (a transaction manager over that pool; the platform manager governs a
     * different database and would leave this statement in autocommit). Under the old blanket catch
     * this run took the escape, whose first {@code ALTER} then failed {@code 25P02} inside the aborted
     * transaction, and the operator was told "current transaction is aborted" instead of the truth —
     * with, on the shapes where the drop had got further, a slot left behind for nothing.
     */
    @Test
    void aFailureTheEscapeCannotRepairIsRethrownWithTheSlotStillAttached() {
        UUID orgId = organization();
        String silo = silos.place(orgId);
        String slot = TenantReplication.subscriptionName(silo);
        bareStreams(silo);

        DataSource remotePool = pools.poolFor(TenantRemotes.DATASOURCE);
        TransactionTemplate onTheRemote = new TransactionTemplate(new JdbcTransactionManager(remotePool));

        assertThatThrownBy(() -> onTheRemote.executeWithoutResult(status ->
                remoteSide().dropSubscriptionAndSlot(silo, "datasource 'primary'")))
                .describedAs("the caller hears the database's own refusal, not a swallowed one")
                .hasMessageContaining("cannot run inside a transaction block");

        assertThat(remoteSide().subscriptionExists(silo))
                .describedAs("nothing was disassociated, so a retry outside the transaction finishes")
                .isTrue();
        assertThat(slotsOnPrimary())
                .describedAs("and the slot is still ATTACHED — the escape's price was not paid for a"
                        + " failure the escape does not repair")
                .contains(slot);
        assertThat(cutover.reportAbandonedSlots(TenantPlacement.PRIMARY_DATASOURCE))
                .describedAs("an attached slot is not abandoned, and the sweep must not claim it is")
                .doesNotContain(slot);

        // Outside the transaction the same call completes, which is the point of rethrowing.
        assertThat(remoteSide().dropSubscriptionAndSlot(silo, "datasource 'primary'")).isEmpty();
        assertThat(slotsOnPrimary()).doesNotContain(slot);
    }

    // -------------------------------------------------------------------------------- the refusals

    /**
     * Every refusal that stands between an operator and an irreversible mistake, asserted on the
     * sentence the operator will read — the message IS the interface for an operator-initiated
     * runbook step (ADR 0011 §10 Q6), so a refusal that fired with the wrong explanation would be a
     * defect even though the state was protected.
     */
    @Test
    void theRefusalsThatStandBetweenAnOperatorAndAnIrreversibleMistake() {
        UUID pooled = organization();
        TenantContext.runAsPlatform(() -> placements.announce(pooled, TenantSchemas.TENANT_POOL));
        assertThatThrownBy(() ->
                cutover.moveToDatasource(pooled, TenantRemotes.DATASOURCE, forwardLinks()))
                .isInstanceOf(TenantCutoverException.class)
                .describedAs("§7.1: a row-filtered publication breaks every pooled tenant's UPDATE and"
                        + " DELETE fleet-wide at DML time, so the hop discipline is 0->1->2")
                .hasMessageContaining("is pooled")
                .hasMessageContaining("promote it first");

        UUID siloed = organization();
        silos.place(siloed);
        assertThatThrownBy(() -> cutover.moveToDatasource(
                siloed, TenantPlacement.PRIMARY_DATASOURCE, forwardLinks()))
                .isInstanceOf(TenantCutoverException.class)
                .hasMessageContaining("there is nothing to move");
        assertThat(TenantContext.callAsPlatform(() -> cutovers.find(siloed)))
                .describedAs("a refused move records nothing — the refusals all fire before the row")
                .isEmpty();

        // A SYNCING move has nothing to roll back and nothing insured to decommission.
        String silo = TenantSchemas.siloSchema(siloed);
        assertThat(TenantContext.callAsPlatform(() -> cutovers.begin(
                siloed, silo, TenantPlacement.PRIMARY_DATASOURCE, TenantRemotes.DATASOURCE))).isTrue();
        assertThatThrownBy(() -> cutover.rollBack(siloed))
                .isInstanceOf(TenantCutoverException.class)
                .hasMessageContaining("not WATCHING")
                .hasMessageContaining("abort() it");
        assertThatThrownBy(() -> cutover.decommission(siloed))
                .isInstanceOf(TenantCutoverException.class)
                .hasMessageContaining("no completed, insured move to decommission");
        assertThatThrownBy(() -> cutover.reclaimAbandonedCopy(siloed, TenantRemotes.DATASOURCE))
                .describedAs("while a cutover lives, EVERY copy is load-bearing")
                .isInstanceOf(TenantCutoverException.class)
                .hasMessageContaining("while it lives");
        TenantContext.runAsPlatform(() -> cutovers.end(siloed));

        // Served from the target already: the flip stands, so abort is not the reversal.
        assertThat(TenantContext.callAsPlatform(() -> cutovers.begin(
                siloed, silo, TenantRemotes.DATASOURCE, TenantPlacement.PRIMARY_DATASOURCE))).isTrue();
        assertThatThrownBy(() -> cutover.abort(siloed))
                .isInstanceOf(TenantCutoverException.class)
                .hasMessageContaining("the flip stands");
        TenantContext.runAsPlatform(() -> cutovers.end(siloed));
    }

    /**
     * Decommission destroys the source copy and with it the cheap reversal, so it is refused until the
     * watch window has run (§10 Q4: the thing the window insures against is a serving defect found on
     * day three). The window here is ten minutes and this test does not wait for it — the assertion is
     * the refusal, and the refusal must name the setting so the operator is not left guessing whether
     * it is a flag or an ADR question.
     */
    @Test
    void decommissionIsRefusedUntilTheWatchWindowHasInsuredTheMove() {
        UUID orgId = organization();
        String silo = remotes.placeOnRemote(orgId);
        assertThat(TenantContext.callAsPlatform(() -> cutovers.begin(
                orgId, silo, TenantPlacement.PRIMARY_DATASOURCE, TenantRemotes.DATASOURCE))).isTrue();
        TenantContext.runAsPlatform(() -> {
            cutovers.markCut(orgId);
            cutovers.markWatching(orgId);
        });

        assertThatThrownBy(() -> cutover.decommission(orgId))
                .isInstanceOf(TenantCutoverException.class)
                .hasMessageContaining("app.tenancy.cutover.watch-window")
                .hasMessageContaining("decommissioning destroys it");

        assertThat(cutoverRow(orgId).state())
                .describedAs("a refused decommission changes nothing")
                .isEqualTo(State.WATCHING);
    }

    // ------------------------------------------------------------------------ fixture and measurement

    private UUID organization() {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(),
                "ext-" + UUID.randomUUID());
        touched.add(orgId);
        return orgId;
    }

    /**
     * Steps 1-3 exactly as {@link TenantCutover#moveToDatasource} spells them, and no further — a
     * SYNCING row with live streams and no flip, which is what a process killed during the online
     * phase leaves behind.
     *
     * <p>The wait for the initial sync is not padding: a subscription torn down mid-tablesync leaves
     * the outcome to timing, and a teardown test whose starting state is "whatever the copier had
     * reached" is a teardown test that passes or fails on the box's mood.
     */
    private void syncingCutover(UUID orgId, String silo) {
        assertThat(TenantContext.callAsPlatform(() ->
                cutovers.begin(orgId, silo, TenantPlacement.PRIMARY_DATASOURCE, TenantRemotes.DATASOURCE)))
                .isTrue();
        destinations.build(pools.poolFor(TenantRemotes.DATASOURCE), silo, false);
        primarySide().createPublication(silo);
        remoteSide().createSubscription(silo, forwardLinks().sourceConninfo(),
                TenantReplication.publicationName(silo), true);
        awaitInitialSync(silo);
    }

    /**
     * The replication objects with NO cutover row behind them — the shape the two slot tests need,
     * because a row would explain the slot and the whole question there is what happens to a slot
     * nothing explains.
     */
    private void bareStreams(String silo) {
        destinations.build(pools.poolFor(TenantRemotes.DATASOURCE), silo, false);
        primarySide().createPublication(silo);
        remoteSide().createSubscription(silo, forwardLinks().sourceConninfo(),
                TenantReplication.publicationName(silo), true);
        awaitInitialSync(silo);
    }

    /** Every relation of the forward subscription at state {@code r}. See {@link #syncingCutover}. */
    private void awaitInitialSync(String silo) {
        Instant deadline = Instant.now().plusSeconds(120);
        while (remoteSide().unsyncedRelations(silo) != 0) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("the initial table sync of " + silo + " never completed, so"
                        + " the state this test tears down was never reached");
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting on replication", interrupted);
        }
    }

    private Cutover cutoverRow(UUID orgId) {
        return TenantContext.callAsPlatform(() -> cutovers.find(orgId)).orElseThrow();
    }

    private TenantReplication primarySide() {
        return new TenantReplication(
                new JdbcTemplate(pools.poolFor(TenantPlacement.PRIMARY_DATASOURCE)),
                "datasource 'primary'");
    }

    private TenantReplication remoteSide() {
        return new TenantReplication(remoteJdbc(), "datasource '" + TenantRemotes.DATASOURCE + "'");
    }

    private JdbcTemplate remoteJdbc() {
        return new JdbcTemplate(pools.poolFor(TenantRemotes.DATASOURCE));
    }

    private CutoverLinks forwardLinks() {
        return new CutoverLinks(bridgeConninfo(POSTGRES), bridgeConninfo(TenantRemotes.container()));
    }

    /**
     * libpq conninfo for the far side of a subscription: the container's DEFAULT-BRIDGE address,
     * because the database server dialling it lives in another container where the host-mapped port
     * does not exist ({@link CutoverLinks}' own reasoning).
     */
    private static String bridgeConninfo(PostgreSQLContainer container) {
        var networks = container.getContainerInfo().getNetworkSettings().getNetworks();
        String address = networks.values().iterator().next().getIpAddress();
        return "host=" + address + " port=5432 dbname=" + container.getDatabaseName()
                + " user=" + container.getUsername() + " password=" + container.getPassword();
    }

    private UUID ticketOnPrimary(String silo, UUID orgId, UUID opener, String subject) {
        UUID id = UUID.randomUUID();
        TenantContext.runAsPlatform(() -> jdbc.update("insert into " + quoted(silo) + ".ticket (id,"
                + " org_id, opener_person_id, subject, priority, status, first_response_due_at,"
                + " resolution_due_at, escalated, version, created_at) values (?, ?, ?, ?, 'P3',"
                + " 'OPEN', now() + interval '1 hour', now() + interval '1 day', false, 0, now())",
                id, orgId, opener, subject));
        return id;
    }

    /** Schema-qualified on the platform axis: the question is about a database, not about a route. */
    private long countOnPrimary(String silo, String table, UUID id) {
        return TenantContext.callAsPlatform(() -> Optional.ofNullable(jdbc.queryForObject(
                "select count(*) from " + quoted(silo) + "." + table + " where id = ?",
                Long.class, id)).orElse(0L));
    }

    /** The same count over a DIRECT connection to a named container — no router, no search_path. */
    private static long countOn(Connection side, String silo, String table, UUID id)
            throws SQLException {
        try (PreparedStatement statement = side.prepareStatement(
                "select count(*) from " + quoted(silo) + "." + table + " where id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    /** Every cutover-shaped slot on the platform database, read directly rather than through the class under test. */
    private List<String> slotsOnPrimary() {
        return TenantContext.callAsPlatform(() -> jdbc.queryForList(
                "select slot_name from pg_replication_slots where slot_name ~ '^s_t_[0-9a-f]{32}$'"
                        + " order by slot_name", String.class));
    }

    /**
     * The walsender lets go of a slot asynchronously once its subscription is gone, and the reclaim's
     * {@code not active} predicate is what keeps it from touching a live stream — so waiting for that
     * transition is part of reproducing the abandoned state, not a flake patch.
     */
    private void awaitSlotInactive(String slot) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Boolean.TRUE.equals(TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select coalesce(bool_or(active), false) from pg_replication_slots where slot_name = ?",
                Boolean.class, slot)))) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("replication slot '" + slot + "' is still ACTIVE 30s after its"
                        + " subscription was dropped — the abandoned state this test needs never"
                        + " happened, so what follows would prove nothing");
            }
            sleep(250);
        }
    }

    private static boolean regclassIsNull(Connection side, String qualifiedTable) throws SQLException {
        try (PreparedStatement statement = side.prepareStatement("select to_regclass(?) is null")) {
            statement.setString(1, qualifiedTable);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------------- replication cleanup

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
