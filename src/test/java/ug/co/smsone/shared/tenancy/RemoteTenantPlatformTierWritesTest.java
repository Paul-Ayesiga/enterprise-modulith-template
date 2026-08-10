package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;
import ug.co.smsone.search.SearchDoc;
import ug.co.smsone.search.SearchIndex;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.events.EventInbox;
import ug.co.smsone.shared.idempotency.IdempotencyStore;
import ug.co.smsone.shared.queue.QueueSignals;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantRemotes;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>The test the Phase 7 suite was missing: a tenant on a SECOND DATABASE doing the ordinary
 * platform-tier work of an ordinary request</b> — claiming an idempotency key, writing an audit row,
 * indexing a search document, queueing a notification, and announcing queued work — against two real
 * Postgres containers (ADR 0011 §5.1, ADR 0003).
 *
 * <h2>Why the 981-test suite was green while every one of these was broken</h2>
 *
 * <p>Because nothing exercised a remote tenant doing a platform-tier write. {@code
 * TenantRemoteRoutingTest} proved the ROUTER — a tenant's own rows land on its own database — and
 * proved §5.1's tripwire fires for a deliberately stray statement. What no test asked was whether the
 * SHIPPED call sites are on the right side of that tripwire, and they were not: {@code IdempotencyStore
 * .claim} runs on the request's tenant axis because {@code CurrentUserFilter} ({@code @Order(-1)}) pins
 * it before {@code IdempotencyFilter} ({@code @Order(1)}) is reached, and {@code platform
 * .idempotency_key} does not exist on a remote. Every idempotent POST to such a tenant would have
 * 500'd before its controller ran.
 *
 * <p>Each method below therefore reproduces the exact axis and transaction state its production caller
 * produces, and asserts the row's <b>physical location</b> over a DIRECT connection to the container it
 * should be in — never through the router, which would ask the same component the same question twice
 * and pass with every conversion absent. The remote's {@code platform} schema holds only {@code
 * event_publication}, so "wrote to the wrong database" cannot degrade into "wrote somewhere harmless".
 *
 * <p><b>Against the code before the conversions every one of these fails identically:</b>
 * {@code org.postgresql.util.PSQLException: ERROR: relation "platform.idempotency_key" does not exist}
 * (and {@code platform.event_inbox}, {@code platform.queue_signal}, {@code platform.notification_delivery},
 * {@code platform.search_document}, {@code platform.audit_log} for the others), surfaced by Spring as
 * {@code BadSqlGrammarException}.
 */
class RemoteTenantPlatformTierWritesTest extends AbstractIntegrationTest {

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
    private IdempotencyStore idempotency;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private Notifications notifications;

    @Autowired
    private EventInbox inbox;

    @Autowired
    private QueueSignals signals;

    @Autowired
    private SearchIndex searchIndex;

    @Autowired
    private TransactionTemplate transactions;

    /**
     * <b>The confirmed-critical one.</b> The state is exactly what the filter chain produces: the org
     * pinned, no transaction open (the claim happens before the controller's and the completion after
     * it commits). Every one of the four statements has to reach primary from a connection that is
     * pointed at another database entirely.
     */
    @Test
    void anIdempotentPostFromARemoteTenantClaimsAndCompletesItsKeyOnPrimary() throws SQLException {
        UUID orgId = remoteOrg("idem");
        String principal = "person:" + UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();

        Optional<Instant> claimedAt = TenantContext.callAs(orgId,
                () -> idempotency.claim(principal, key, "hash-1", Duration.ofMinutes(5)));
        assertThat(claimedAt).as("the claim is the FIRST thing an idempotent POST does, and it is"
                + " issued on the tenant's connection — that is the whole defect").isPresent();

        // A second claim of the same key must lose, which proves the row is really there and really
        // being read back — not that the statement merely did not throw.
        assertThat(TenantContext.callAs(orgId,
                () -> idempotency.claim(principal, key, "hash-1", Duration.ofMinutes(5))))
                .as("the key is claimed; a duplicate request must fall through to the stored response")
                .isEmpty();

        TenantContext.runAs(orgId, () ->
                idempotency.complete(principal, key, claimedAt.get(), 201, "{\"data\":{}}", "application/json"));

        IdempotencyStore.StoredResponse stored = TenantContext.callAs(orgId,
                () -> idempotency.find(principal, key)).orElseThrow();
        assertThat(stored.inProgress()).isFalse();
        assertThat(stored.status()).isEqualTo(201);

        assertThat(rowsOnPrimary(
                "select count(*) from platform.idempotency_key where principal = ? and idem_key = ?",
                principal, key))
                .as("the key is physically on PRIMARY, where idempotency_key lives and only lives")
                .isEqualTo(1);
        assertThat(platformTableExistsOnRemote("idempotency_key"))
                .as("and the remote has no such table at all — ADR 0011 §5.1's tripwire, which is why"
                        + " an unconverted claim is a 500 and not a row in a copy nobody reads")
                .isFalse();

        TenantContext.runAs(orgId, () -> idempotency.release(principal, key, claimedAt.get()));
        assertThat(rowsOnPrimary(
                "select count(*) from platform.idempotency_key where principal = ? and idem_key = ?",
                principal, key))
                .as("release reaches the same row it claimed").isZero();
    }

    /**
     * <b>An audited action, in both directions across the boundary.</b> {@code AuditLog.record} runs
     * inside the caller's transaction by contract, and the caller is frequently not on the axis of the
     * org it records against — which since ADR 0011 means a schema name and a connection that name two
     * different servers. Three rows, three placements, each asserted where it physically is.
     */
    @Test
    void anAuditedActionSplitsBetweenTheRemoteSiloAndPrimaryByOrgIdAndNothingElse() throws SQLException {
        UUID orgId = remoteOrg("audit");
        String silo = TenantSchemas.siloSchema(orgId);
        String orgAction = "REMOTE_TENANT_ACTION_" + UUID.randomUUID();
        String platformAction = "REMOTE_TENANT_PLATFORM_EVENT_" + UUID.randomUUID();
        String operatorAction = "OPERATOR_ACTION_ON_REMOTE_" + UUID.randomUUID();

        // (1) and (2): the tenant's own request, inside its own transaction on its own database,
        // recording one row about itself and one platform-level row. They must go to two DIFFERENT
        // SERVERS, decided by org_id alone.
        TenantContext.runAs(orgId, () -> transactions.executeWithoutResult(tx -> {
            auditLog.record(orgAction, orgId, "target", null, "done");
            auditLog.record(platformAction, null, "target", null, "done");
        }));

        // (3): a platform operator — or any job — recording against this tenant from the platform axis,
        // inside a transaction on PRIMARY. The row belongs in the tenant's silo, which is not here.
        TenantContext.runAsPlatform(() -> transactions.executeWithoutResult(tx ->
                auditLog.record(operatorAction, orgId, "target", null, "done")));

        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(countOn(remote, "select count(*) from " + silo + ".audit_log where action = ?",
                    orgAction))
                    .as("the tenant's own compliance row is in its silo, on its own database")
                    .isEqualTo(1);
            assertThat(countOn(remote, "select count(*) from " + silo + ".audit_log where action = ?",
                    operatorAction))
                    .as("and so is the operator's row about it — written from a PRIMARY connection,"
                            + " which is the direction SplitTables.homeOf alone cannot address")
                    .isEqualTo(1);
        }
        assertThat(platformTableExistsOnRemote("audit_log"))
                .as("the remote holds no platform.audit_log, so the platform-level row had nowhere"
                        + " quiet to land").isFalse();
        assertThat(rowsOnPrimary("select count(*) from platform.audit_log where action = ?", platformAction))
                .as("the platform-level row stayed on primary, where a support investigation can find it")
                .isEqualTo(1);
        assertThat(rowsOnPrimary("select count(*) from platform.audit_log where action = ?", orgAction))
                .as("and the tenant's row did not leak into the platform half").isZero();
    }

    /**
     * <b>A queued notification.</b> {@code Notifications.dispatch} is a port other modules call from
     * inside org requests, so it runs on the tenant's axis; both the delivery row and its
     * {@code queue_signal} are platform-tier and must reach primary. This is also the one queue for
     * which ADR 0011 §5.2 loses nothing — rows and signal are both on primary, so they still commit
     * together — and the assertion that both are there is what says so.
     */
    @Test
    void aQueuedNotificationFromARemoteTenantIsEnqueuedAndAnnouncedOnPrimary() throws SQLException {
        UUID orgId = remoteOrg("notify");
        String recipient = "queued-" + UUID.randomUUID() + "@example.test";

        TenantContext.runAs(orgId, () -> notifications.dispatch(new NotificationRequest(
                "Remote tenant notification", "body",
                List.of(Recipient.email(recipient)), Map.of("orgId", orgId.toString()))));

        assertThat(rowsOnPrimary(
                "select count(*) from platform.notification_delivery where recipient = ? and org_id = ?",
                recipient, orgId))
                .as("the delivery row is on primary, where notification_delivery lives (ADR 0010 §2 row 27)")
                .isEqualTo(1);
        assertThat(rowsOnPrimary(
                "select count(*) from platform.queue_signal where queue = 'notification' and org_id = ?",
                orgId))
                .as("and the signal that announces it is on primary too — without it the worker never"
                        + " looks at this tenant and the message is queued forever")
                .isEqualTo(1);
        assertThat(platformTableExistsOnRemote("notification_delivery")).isFalse();
    }

    /**
     * <b>The webhook fan-out's two platform-tier writes, and the ADR 0011 §5.2 ordering.</b>
     * {@code WebhookEventListener} pins the EVENT'S org and opens a transaction inside the pin
     * (ADR 0010 §3.2 requires it — its tables are tenant-tier), and inside that transaction the
     * dispatcher takes an {@code event_inbox} claim and the queue raises a {@code queue_signal}. Both
     * are platform-tier, both are on the other database, and both used to be issued on the tenant's
     * connection.
     *
     * <p>The second assertion is the invariant decision itself: for a remote tenant the raise is
     * DEFERRED to after the rows commit, so mid-transaction there must be no signal, and after commit
     * there must be one. Read over a direct connection to primary because a routed read inside the
     * transaction would either join it or be refused a pin.
     */
    @Test
    void aWebhookFanOutFromARemoteTenantClaimsItsInboxRowAndRaisesItsSignalAfterTheRowsCommit()
            throws SQLException {
        UUID orgId = remoteOrg("fanout");
        String messageId = "member.added:" + UUID.randomUUID();

        TenantContext.runAs(orgId, () -> transactions.executeWithoutResult(tx -> {
            assertThat(inbox.recordIfNew("webhooks", messageId))
                    .as("the dedup claim is the dispatcher's first statement and it is platform-tier")
                    .isTrue();
            signals.raise("webhook", orgId);
            try {
                assertThat(signalCount(orgId))
                        .as("ADR 0011 §5.2: across two databases the signal may NOT commit before the"
                                + " rows — a probe would delete it for rows that then appear")
                        .isZero();
            } catch (SQLException failure) {
                throw new IllegalStateException("could not read primary directly", failure);
            }
        }));

        assertThat(signalCount(orgId))
                .as("and it IS raised once they have — the deferral is to afterCommit, not to nowhere")
                .isEqualTo(1);
        assertThat(rowsOnPrimary(
                "select count(*) from platform.event_inbox where listener_id = 'webhooks' and message_id = ?",
                messageId))
                .as("the dedup claim landed on primary, which is where the redelivery will look for it")
                .isEqualTo(1);
        assertThat(platformTableExistsOnRemote("event_inbox")).isFalse();
        assertThat(platformTableExistsOnRemote("queue_signal")).isFalse();

        // The failure repair: handing the claim back is what keeps an at-least-once redelivery alive
        // when the claim could not roll back with the work it guards.
        inbox.forget("webhooks", messageId);
        assertThat(TenantContext.callAs(orgId, () -> inbox.recordIfNew("webhooks", messageId)))
                .as("after forget, the redelivery processes instead of being de-duplicated into silence")
                .isTrue();
    }

    /**
     * <b>ADR 0011 §5.2's reconciling sweep, on the state it exists for.</b> The residue is queued work
     * with no signal — what a process death between the two commits leaves behind — and it is invisible
     * to every poll, forever, because polls read the signal. This drives the repair directly on that
     * state: a signal that is missing is created, and a signal that is already there is never disturbed,
     * because disturbing one voids a live worker's lease.
     */
    @Test
    void theReconcilingSweepAnnouncesOrphanedWorkAndLeavesAHealthySignalAlone() {
        UUID orgId = remoteOrg("sweep");
        Instant due = Instant.now().minusSeconds(30);

        assertThat(signals.announceIfUnsignalled("webhook", orgId, due))
                .as("work with no signal is orphaned — this is the only thing that will ever find it")
                .isTrue();

        assertThat(signals.announceIfUnsignalled("webhook", orgId, due.minusSeconds(600)))
                .as("a scope that already has a signal is left exactly as it is: pulling due_at forward"
                        + " or clearing the lease would park a tenant behind a lease nobody holds")
                .isFalse();

        Instant dueAt = TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select due_at from platform.queue_signal where queue = 'webhook' and org_id = ?",
                java.sql.Timestamp.class, orgId)).toInstant();
        assertThat(dueAt)
                .as("clamped to now() like every other write to this table — a recovered backlog must"
                        + " not sort ahead of tenants that have been waiting honestly")
                .isAfter(due);
    }

    /**
     * <b>A search-indexed write.</b> {@code search_document} is platform-tier and the {@code SearchIndex}
     * port is called by producers from inside org requests, so it reaches this table on the tenant's
     * axis. Kept as its own method because the conversion belongs to the search module rather than to
     * the platform-tier queues around it: a failure here names one file, and it names it precisely.
     */
    @Test
    void aSearchIndexedWriteFromARemoteTenantLandsOnPrimary() throws SQLException {
        UUID orgId = remoteOrg("search");
        String entityId = UUID.randomUUID().toString();

        TenantContext.runAs(orgId, () ->
                searchIndex.upsert(new SearchDoc(orgId, "ticket", entityId, "remote tenant ticket", "body")));

        assertThat(rowsOnPrimary(
                "select count(*) from platform.search_document where entity_type = 'ticket'"
                        + " and entity_id = ?", entityId))
                .as("the projection row is on primary, where the admin search reads it")
                .isEqualTo(1);
        assertThat(platformTableExistsOnRemote("search_document")).isFalse();
    }

    /** A fresh organization served from the second container, with its silo really built there. */
    private UUID remoteOrg(String label) {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), label + "-" + UUID.randomUUID());
        remotes.placeOnRemote(orgId);
        return orgId;
    }

    /**
     * A count read on the PLATFORM axis. Safe as an assertion here — unlike the physical reads below —
     * because the platform axis is primary by definition (ADR 0011 §5) and the test would rather fail
     * than pass on a routed read that agreed with a routed write.
     */
    private int rowsOnPrimary(String sql, Object... args) {
        Integer count = TenantContext.callAsPlatform(
                () -> jdbc.queryForObject(sql, Integer.class, args));
        return count == null ? 0 : count;
    }

    /** The signal, read over a DIRECT connection to primary — usable from inside an open transaction. */
    private int signalCount(UUID orgId) throws SQLException {
        try (Connection primary = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return countOn(primary,
                    "select count(*) from platform.queue_signal where queue = 'webhook' and org_id = ?",
                    orgId);
        }
    }

    /**
     * Whether the REMOTE database has a platform-tier table of this name. It must not: the fixture
     * builds §5.1's minimal schema (only {@code event_publication}) and the absence is the tripwire.
     * Asserting it in every method is what stops a future fixture change from turning these tests green
     * for the wrong reason — a write landing in an empty copy nobody reads.
     */
    private boolean platformTableExistsOnRemote(String table) throws SQLException {
        try (Connection remote = TenantRemotes.remoteConnection();
                PreparedStatement statement =
                        remote.prepareStatement("select to_regclass('platform.' || ?) is not null")) {
            statement.setString(1, table);
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
