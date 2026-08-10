package ug.co.smsone.support.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantRemotes;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>{@code platform.ticket_index} for a tenant on a SECOND DATABASE</b> — the case the suite is green
 * without exercising, against two real Postgres containers (ADR 0011 §5.1, ADR 0003).
 *
 * <h2>Why the silo test is not enough</h2>
 *
 * <p>{@link TicketIndexTest} proves the projection across two SCHEMAS, which is Phase 5's boundary. It
 * cannot see Phase 7's: both of its schemas are in one database, so every statement resolves whichever
 * way it is written and a projection maintained by a plain {@code insert into platform.ticket_index}
 * issued on the tenant's connection passes it perfectly. Move the tenant to another server and that
 * same statement is {@code relation "platform.ticket_index" does not exist} — a 500 on every ticket a
 * remote tenant opens — because the remote's {@code platform} schema deliberately holds only
 * {@code event_publication} (ADR 0011 §5.1's tripwire). Each assertion below therefore checks a row's
 * <b>physical location</b> over a DIRECT connection to the container it should be in, never through the
 * router, which would ask the same component the same question twice.
 *
 * <p>The reconciler has the sharper version of the same problem, and {@link #theReconcilerRemovesAQueueRowForATicketDeletedOnTheRemote}
 * is what pins it: the obvious repair — {@code insert into platform.ticket_index select … from ticket} —
 * is not a slow statement across two databases, it is not a statement at all. Every page has to move
 * through the JVM, and this is the test that fails if anyone ever "simplifies" it back into one SQL
 * pass.
 */
class RemoteTenantTicketIndexTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @RegisterExtension
    final TenantRemotes remotes = new TenantRemotes();

    @DynamicPropertySource
    static void remoteDatasource(DynamicPropertyRegistry registry) {
        TenantRemotes.register(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SupportService support;

    @Autowired
    private TicketFanOut homes;

    @Autowired
    private TicketIndexReconciler reconciler;

    /**
     * The write path across the boundary: the ticket belongs on the remote and its queue row belongs on
     * primary, and the split is decided by the tier alone. Against a projection maintained without
     * {@code CrossDatabaseWrites} this method does not fail an assertion — it throws
     * {@code BadSqlGrammarException} out of {@code support.open}, which is what a remote tenant's very
     * first ticket would have done in production.
     */
    @Test
    void aTicketOpenedByARemoteTenantLivesOnTheRemoteAndItsQueueRowOnPrimary() throws SQLException {
        UUID orgId = remoteOrg();
        String silo = TenantSchemas.siloSchema(orgId);

        UUID ticket = open(orgId, "remote tenant ticket");

        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(countOn(remote, "select count(*) from " + silo + ".ticket where id = ?", ticket))
                    .as("the ticket itself is on the tenant's own database, in its own schema")
                    .isEqualTo(1);
        }
        assertThat(platformTableExistsOnRemote())
                .as("and the remote holds no platform.ticket_index at all — ADR 0011 §5.1's tripwire,"
                        + " which is why an unconverted write is a 500 and not a row in a copy nobody"
                        + " reads")
                .isFalse();
        assertThat(indexRowsOnPrimary(ticket))
                .as("the queue row is on PRIMARY, where the operator's cross-tenant page reads it")
                .isEqualTo(1);
        assertThat(queueIds())
                .as("and the operator sees the remote tenant's ticket without the desk ever dialling"
                        + " that database")
                .contains(ticket.toString());
    }

    /**
     * An update on the remote has to move the queue row on primary. Two databases, no shared
     * transaction, and the pair is eventually consistent by construction (ADR 0011 §5.1) — what must
     * still hold is that the platform half is actually issued, on the right server.
     */
    @Test
    void aStatusChangeOnTheRemoteMovesTheQueueRowOnPrimary() {
        UUID orgId = remoteOrg();
        UUID ticket = open(orgId, "remote status change");

        TenantContext.runAs(orgId, () -> support.changeStatus(ticket, "WAITING_ON_CUSTOMER"));

        assertThat(statusOnPrimary(ticket)).isEqualTo("WAITING_ON_CUSTOMER");
    }

    /**
     * <b>The reconciler, with the two halves of its work on two servers.</b> The residue here is what a
     * cross-database write leaves when the tenant half never lands (or is later purged): a queue row on
     * primary for a ticket the remote does not have. Nothing on the write path can ever remove it — the
     * ticket's own database has no way to reach the projection — so this is the state ADR 0010 §8 Q2's
     * rule exists for, and the only thing that will ever find it.
     */
    @Test
    void theReconcilerRemovesAQueueRowForATicketDeletedOnTheRemote() throws SQLException {
        UUID orgId = remoteOrg();
        String silo = TenantSchemas.siloSchema(orgId);
        UUID ticket = open(orgId, "deleted on the remote");
        assertThat(indexRowsOnPrimary(ticket)).isEqualTo(1);

        // Straight at the remote container, which is exactly what SoftDeletePurgeJob's raw delete looks
        // like from primary's point of view: the row goes and nothing on this side is told.
        try (Connection remote = TenantRemotes.remoteConnection();
                PreparedStatement delete =
                        remote.prepareStatement("delete from " + silo + ".ticket where id = ?")) {
            delete.setObject(1, ticket);
            assertThat(delete.executeUpdate()).isEqualTo(1);
        }
        assertThat(indexRowsOnPrimary(ticket))
                .as("residue: the operator's queue still lists a ticket that no longer exists on the"
                        + " database that owns it")
                .isEqualTo(1);

        reconciler.reconcileEveryHome();

        assertThat(indexRowsOnPrimary(ticket))
                .as("one pass removes it — and it can only do so by asking the REMOTE which of primary's"
                        + " queue rows it still has, which is not a question any single SQL statement"
                        + " can ask")
                .isZero();
    }

    /**
     * The other direction across the boundary: a ticket written straight into the remote silo, which no
     * platform-side statement could have seen. This is every ticket a remote tenant held before V61, and
     * the refresh arm is the only backfill they will ever get.
     */
    @Test
    void theReconcilerBacksFillsATicketThatOnlyExistsOnTheRemote() {
        UUID orgId = remoteOrg();
        UUID ticket = open(orgId, "backfilled from the remote");
        TenantContext.runAsPlatform(() ->
                jdbc.update("delete from platform.ticket_index where ticket_id = ?", ticket));
        assertThat(indexRowsOnPrimary(ticket)).isZero();

        reconciler.reconcileEveryHome();

        assertThat(indexRowsOnPrimary(ticket))
                .as("a live ticket on another database that the operator's queue cannot show is a"
                        + " customer the support desk cannot see")
                .isEqualTo(1);
    }

    /**
     * The single-ticket routes must keep reading the tenant's OWN database. The index's hint points at
     * the right organization; what the operator gets back has to be the remote's row, not a rendering of
     * the projection — otherwise the desk would be answering from a copy and an operator could act on a
     * ticket whose real state had moved.
     */
    @Test
    void theOperatorsSingleTicketRouteStillReadsTheRemoteTenantsOwnRow() {
        UUID orgId = remoteOrg();
        UUID ticket = open(orgId, "read through to the remote");
        // Changed behind the index's back, on the tenant's own axis and therefore on its own database.
        TenantContext.runAs(orgId, () ->
                jdbc.update("update ticket set subject = ? where id = ?", "changed on the remote", ticket));

        String subject = homes.onTicketsHome(ticket, () -> support.requireAnyOrg(ticket).getSubject());

        assertThat(subject)
                .as("the detail read is the tenant's, always — the index renders the queue and answers"
                        + " nothing")
                .isEqualTo("changed on the remote");
        assertThat(subjectOnPrimary(ticket))
                .as("and the projection on primary is still showing the old title, which is the honest"
                        + " state of an operator convenience between writes — the point being that the"
                        + " answer above did NOT come from here")
                .isEqualTo("read through to the remote");
    }

    /** A fresh organization served from the second container, with its silo really built there. */
    private UUID remoteOrg() {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "rti-" + UUID.randomUUID());
        remotes.placeOnRemote(orgId);
        return orgId;
    }

    private UUID open(UUID orgId, String subject) {
        UUID opener = EdgeSeed.person(jdbc, "rti-opener-" + UUID.randomUUID());
        return TenantContext.callAs(orgId,
                () -> support.open(orgId, opener, subject, null, "P3").getId());
    }

    /**
     * A count read on the PLATFORM axis, which is primary by definition (ADR 0011 §5). Safe as an
     * assertion because the alternative — reading through the tenant's route — would agree with a
     * routed write for the wrong reason.
     */
    private int indexRowsOnPrimary(UUID ticketId) {
        Integer count = TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select count(*) from platform.ticket_index where ticket_id = ?", Integer.class, ticketId));
        return count == null ? 0 : count;
    }

    private String statusOnPrimary(UUID ticketId) {
        return TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select status from platform.ticket_index where ticket_id = ?", String.class, ticketId));
    }

    private String subjectOnPrimary(UUID ticketId) {
        return TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select subject from platform.ticket_index where ticket_id = ?", String.class, ticketId));
    }

    /** The operator queue, walked to the end — {@code TicketIndexTest.queueIds}' reasoning verbatim. */
    private List<String> queueIds() {
        List<String> walked = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            var result = homes.queue(null, new CursorPageRequest(CursorPageRequest.MAX_SIZE, cursor),
                    row -> row.ticketId().toString());
            walked.addAll(result.items());
            cursor = result.page().nextCursor();
            if (cursor == null) {
                return walked;
            }
        }
        throw new AssertionError("the operator queue still had pages after 50");
    }

    /**
     * Whether the REMOTE database has a {@code platform.ticket_index}. It must not: the fixture builds
     * ADR 0011 §5.1's minimal schema and the absence IS the tripwire. Asserting it is what stops a
     * future fixture change from turning this class green for the wrong reason — a queue row landing in
     * an empty copy nobody reads.
     */
    private boolean platformTableExistsOnRemote() throws SQLException {
        try (Connection remote = TenantRemotes.remoteConnection();
                PreparedStatement statement = remote.prepareStatement(
                        "select to_regclass('platform.ticket_index') is not null")) {
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static int countOn(Connection connection, String sql, Object arg) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, arg);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }
}
