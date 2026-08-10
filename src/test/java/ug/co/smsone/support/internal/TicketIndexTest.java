package ug.co.smsone.support.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>{@code platform.ticket_index} — the cross-tenant operator queue as a projection, and the
 * reconciler without which it would be a growing pile of lies</b> (V61; ADR 0010 §5.1, §8 Q1's
 * re-measurement and §8 Q2's rule).
 *
 * <p>Two claims are under test and they fail in opposite directions. <b>The projection must be
 * complete</b> — a live ticket missing from the queue is a customer whose problem the support desk
 * never sees, which is the Phase 5 failure this module already paid for once, and it must hold across
 * a POOLED tenant and a SILOED one because those are two different schemas and, since ADR 0011,
 * potentially two different databases. <b>And it must hold nothing else</b> — the paths that make a
 * ticket disappear are all raw SQL that cannot know this table exists, so without a reconciler the
 * queue accumulates rows for tickets nobody can open, forever.
 *
 * <p>{@link #theQueueKeepsShowingAHardDeletedTicketUntilTheReconcilerRuns} is the one that would fail
 * if the index were merely written and never reconciled: it drives the exact statement
 * {@code SoftDeletePurgeJob} issues against an aged-out ticket and then asserts, in order, that the
 * queue still lists it (proving the write path alone cannot clean up after itself) and that one
 * reconciler pass removes it (proving the repair is real rather than declared).
 *
 * <p>{@code SupportDeskFanOutTest} is the sibling: it pins what the operator's SINGLE-TICKET routes and
 * the SLA sweep still reach per home, which is the half that deliberately did not move to the index.
 */
class TicketIndexTest extends AbstractIntegrationTest {

    /** Pages of {@link CursorPageRequest#MAX_SIZE} the queue walk will take before it gives up loudly. */
    private static final int MAX_PAGES = 50;

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SupportService support;

    @Autowired
    private TicketFanOut homes;

    @Autowired
    private TicketIndexReconciler reconciler;

    /**
     * The write path, on both sides of the tenancy boundary in one test, because "it works pooled" is
     * exactly what was true before Phase 5 and exactly what stopped being enough.
     */
    @Test
    void aTicketOpenedInThePoolAndOneOpenedInASiloBothReachTheOperatorQueue() {
        UUID pooled = organization();
        UUID siloed = organization();
        silos.place(siloed);

        UUID pooledTicket = open(pooled, "pooled ticket");
        UUID siloedTicket = open(siloed, "siloed ticket");
        // The pre-assertion every fan-out gate in this suite needs: without it, a router that had
        // quietly stopped working would put BOTH rows in tenant_pool and every assertion below would
        // pass with the silo empty from creation to drop.
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "ticket", "id", siloedTicket);

        assertThat(queueIds()).contains(pooledTicket.toString(), siloedTicket.toString());
        assertThat(indexedOrgOf(siloedTicket))
                .as("the queue row carries the organization, which is what lets the operator's"
                        + " single-ticket routes go straight to the right home instead of probing"
                        + " every one of them")
                .isEqualTo(siloed);
    }

    /**
     * <b>The queue is READ from the projection, not merged from the homes — falsifiably.</b> A test
     * that only asserted "the siloed ticket is listed" would pass just as well against the per-home
     * merge this replaced, so it would prove nothing about V61. Deleting the tenant's own row and
     * finding the ticket still listed can only be true if the page came from
     * {@code platform.ticket_index}.
     */
    @Test
    void theQueuePageComesFromTheIndexAndNotFromTheTenantSchemas() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID ticket = open(siloed, "read from the index");

        hardDeleteTicketInItsHome(siloed, ticket);

        assertThat(queueIds())
                .as("the tenant's row is gone and the operator still sees the queue row — which is only"
                        + " possible if the page is the projection's. It is also precisely the residue"
                        + " the reconciler exists to remove")
                .contains(ticket.toString());
    }

    /** Every mutation that moves a column the queue renders has to move the queue with it. */
    @Test
    void anAssignmentAndAStatusChangeInASiloAreReflectedInTheQueue() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID ticket = open(siloed, "updated in a silo");
        UUID operator = EdgeSeed.person(jdbc, "queue-operator-" + UUID.randomUUID());

        TenantContext.runAs(siloed, () -> support.assign(ticket, operator));
        assertThat(indexed(ticket))
                .extracting(TicketIndex.Row::assigneePersonId, TicketIndex.Row::status)
                .containsExactly(operator, "IN_PROGRESS");

        TenantContext.runAs(siloed, () -> support.changeStatus(ticket, "WAITING_ON_CUSTOMER"));
        assertThat(indexed(ticket).status())
                .as("a status the queue filters on: an operator narrowing to OPEN must not keep seeing"
                        + " a ticket that has moved on")
                .isEqualTo("WAITING_ON_CUSTOMER");
    }

    /**
     * <b>The test that fails if the index is written and never reconciled.</b>
     *
     * <p>The delete here is {@code SoftDeletePurgeJob}'s own: raw SQL against a soft-deleted row past
     * its retention window. It has to be raw SQL — {@code @SQLRestriction} hides exactly the rows the
     * purge exists to remove — and raw SQL cannot know this projection exists, which is why nothing on
     * the write path can ever clean up after it. That is {@code sweepSearchResidue}'s situation
     * verbatim, and ADR 0010 §8 Q2 turns it into the rule this test enforces.
     */
    @Test
    void theQueueKeepsShowingAHardDeletedTicketUntilTheReconcilerRuns() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID ticket = open(siloed, "aged out and purged");
        assertThat(queueIds()).contains(ticket.toString());

        softDeleteTicketInItsHome(siloed, ticket);
        hardDeleteTicketInItsHome(siloed, ticket);

        assertThat(queueIds())
                .as("residue: the ticket is gone from the tenant and the operator can still see it in"
                        + " the queue, where opening it 404s. Nothing on the write path will ever"
                        + " remove this row — that is the whole reason a projection needs a reconciler")
                .contains(ticket.toString());

        reconciler.reconcileEveryHome();

        assertThat(queueIds())
                .as("and one pass removes it. Delete the reconciler and this assertion is the one that"
                        + " fails, which is the point of the two halves being in one test")
                .doesNotContain(ticket.toString());
    }

    /**
     * A SOFT delete — the row is still there and still invisible to JPA — must take the queue row with
     * it, in both a silo and the pool.
     *
     * <p>The predicate that makes this work is written out by hand in two places
     * ({@code TicketIndexReconciler}'s refresh page and its {@code liveAmong} probe) because
     * {@code @SQLRestriction} does not apply to native SQL (AGENTS §4.2). Drop it from either and the
     * two arms fight: the sweep deletes a soft-deleted ticket's row and the refresh puts it straight
     * back, every night, with the log claiming both worked.
     */
    @Test
    void aSoftDeletedTicketLeavesTheQueueInBothAPooledAndASiloedTenant() {
        UUID pooled = organization();
        UUID siloed = organization();
        silos.place(siloed);
        UUID pooledTicket = open(pooled, "soft-deleted while pooled");
        UUID siloedTicket = open(siloed, "soft-deleted while siloed");
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "ticket", "id", siloedTicket);

        softDeleteTicketInItsHome(pooled, pooledTicket);
        softDeleteTicketInItsHome(siloed, siloedTicket);
        reconciler.reconcileEveryHome();

        assertThat(queueIds()).doesNotContain(pooledTicket.toString(), siloedTicket.toString());
    }

    /**
     * The other direction, and the one that matters to a customer: a ticket the index never learned
     * about. Every ticket that existed before V61 is in this state and no migration could have
     * backfilled them — the platform sequence runs before any tenant schema is reachable, and after
     * Phase 5 a backfill would have to enter every silo — so the reconciler's ordinary insert arm is the
     * backfill, running on its first night.
     */
    @Test
    void theReconcilerBacksFillsATicketThatWasWrittenAroundTheIndex() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID ticket = open(siloed, "written around the index");
        // The pre-V61 state, reproduced exactly: the ticket is in the tenant schema and the projection
        // has never heard of it.
        deleteIndexRow(ticket);
        assertThat(queueIds()).doesNotContain(ticket.toString());

        reconciler.reconcileEveryHome();

        assertThat(queueIds())
                .as("a live ticket the queue cannot show is a customer the support desk cannot see —"
                        + " the harmful direction of drift, and the one the refresh arm exists for")
                .contains(ticket.toString());
    }

    /**
     * <b>The invariant, stated as a test: the index RENDERS, the tenant schema ANSWERS.</b> With the
     * queue row removed and the ticket untouched, the tenant's own listing must be completely
     * unaffected — it reads its own schema and has never read this table. A change that "optimised" the
     * tenant path onto the projection would pass every other test in this class and fail here.
     */
    @Test
    void aTenantReadsItsOwnTicketsFromItsOwnSchemaAndNeverFromTheIndex() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID ticket = open(siloed, "the tenant's own read");

        deleteIndexRow(ticket);

        List<UUID> own = TenantContext.callAs(siloed,
                () -> support.listForOrg(siloed, new CursorPageRequest(50, null)))
                .getContent().stream().map(Ticket::getId).toList();
        assertThat(own)
                .as("the projection is an operator convenience and never the authority; a tenant that"
                        + " lost sight of its own ticket because a platform-side row was missing would"
                        + " have made it one")
                .contains(ticket);
    }

    /** The queue's only filter, over a real silo, because the index carries its own copy of `status`. */
    @Test
    void theStatusFilterNarrowsTheQueueWithoutTouchingATenantSchema() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID stillOpen = open(siloed, "still open");
        UUID closed = open(siloed, "already closed");
        TenantContext.runAs(siloed, () -> support.changeStatus(closed, "CLOSED"));

        List<String> openOnly = queueIds("OPEN");

        assertThat(openOnly).contains(stillOpen.toString());
        assertThat(openOnly).doesNotContain(closed.toString());
    }

    private UUID organization() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "idx-" + UUID.randomUUID());
    }

    /** Opened through the service, on the org's own axis — the path the two controllers both funnel to. */
    private UUID open(UUID orgId, String subject) {
        UUID opener = EdgeSeed.person(jdbc, "idx-opener-" + UUID.randomUUID());
        return TenantContext.callAs(orgId,
                () -> support.open(orgId, opener, subject, null, "P3").getId());
    }

    private List<String> queueIds() {
        return queueIds(null);
    }

    /**
     * The operator queue, through the same call the admin controller makes, <b>walked to the end</b>.
     *
     * <p>One Postgres container serves every cached Spring context in the run, and this collection is
     * fleet-wide by definition — {@code SupportDeskFanOutTest} alone stamps its probes at the year 2099
     * so they sort ahead of everything. Asserting on one page would therefore make every
     * {@code doesNotContain} here mean "was not on the first page", which is not the claim. Walking also
     * exercises the hand-rolled keyset across pages, which is the part of {@code TicketIndex} that could
     * silently skip or repeat a row.
     */
    private List<String> queueIds(String status) {
        List<String> walked = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            var result = homes.queue(status,
                    new CursorPageRequest(CursorPageRequest.MAX_SIZE, cursor),
                    row -> row.ticketId().toString());
            walked.addAll(result.items());
            cursor = result.page().nextCursor();
            if (cursor == null) {
                return walked;
            }
        }
        // Never silently truncate: a short walk would turn a real "the row is still there" into a
        // passing doesNotContain, which is the one way these assertions could lie.
        throw new AssertionError("the operator queue still had pages after " + MAX_PAGES
                + " — raise MAX_PAGES or find out what is filling the shared container");
    }

    private TicketIndex.Row indexed(UUID ticketId) {
        return TenantContext.callAsPlatform(() -> jdbc.queryForObject("""
                select ticket_id, org_id, opener_person_id, subject, category, priority, status,
                       assignee_person_id, escalated, first_response_at, resolution_due_at, created_at
                  from platform.ticket_index where ticket_id = ?
                """, TicketIndex.ROW, ticketId));
    }

    private UUID indexedOrgOf(UUID ticketId) {
        return indexed(ticketId).orgId();
    }

    private void deleteIndexRow(UUID ticketId) {
        TenantContext.runAsPlatform(() ->
                jdbc.update("delete from platform.ticket_index where ticket_id = ?", ticketId));
    }

    /** What a delete path would do — soft, so the row is still there and still invisible to JPA. */
    private void softDeleteTicketInItsHome(UUID orgId, UUID ticketId) {
        TenantContext.runAs(orgId, () ->
                jdbc.update("update ticket set deleted_at = now() where id = ?", ticketId));
    }

    /** What {@code SoftDeletePurgeJob} does once retention has expired: raw SQL, no projection in sight. */
    private void hardDeleteTicketInItsHome(UUID orgId, UUID ticketId) {
        TenantContext.runAs(orgId, () -> jdbc.update("delete from ticket where id = ?", ticketId));
    }
}
