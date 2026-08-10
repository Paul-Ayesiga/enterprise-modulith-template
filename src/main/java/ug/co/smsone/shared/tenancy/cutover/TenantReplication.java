package ug.co.smsone.shared.tenancy.cutover;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * The logical-replication statements of a cutover, bound to ONE side — instantiate one per database.
 * Nothing in here decides anything: sequencing, freezing and flipping are {@link TenantCutover}'s,
 * and this class only knows how to say each step in SQL. Every mechanic below was executed against
 * two scratch Postgres 18.4 containers before it was written down (ADR 0011 §7; the transcript is in
 * the delivery evidence), so where a comment states a behaviour, it was observed.
 *
 * <h2>Naming is the contract</h2>
 *
 * <p>The publication on whichever side currently publishes is {@code p_<schema>}, the subscription on
 * whichever side currently subscribes is {@code s_<schema>}, and a subscription's replication slot on
 * its publisher defaults to the subscription's own name. Direction-neutral on purpose: a reverse
 * cutover is the same machinery with the sides swapped, and the names never collide because the two
 * sides are different databases. The names are derived from a schema that has been through
 * {@link TenantSchemas#requireSiloSchema}, which is the entire injection defence — none of these
 * identifiers can be a bound parameter.
 *
 * <h2>Privileges, stated once</h2>
 *
 * <p>{@code CREATE PUBLICATION … FOR TABLES IN SCHEMA} and {@code CREATE SUBSCRIPTION} both require
 * superuser (18.4), and the subscription's {@code CONNECTION} user needs {@code REPLICATION} on its
 * publisher. The runbook carries this as a prerequisite; here it surfaces as the database's own
 * error, which is the honest failure.
 */
class TenantReplication {

    private static final Logger log = LoggerFactory.getLogger(TenantReplication.class);

    /**
     * SQLSTATE class 08, "connection exception" — the only class of failure that buys the orphaning
     * escape in {@link #dropSubscriptionAndSlot}. Measured: 18.4 reports both "could not connect to
     * publisher …" and "could not drop replication slot … on publisher" as {@code 08006}.
     */
    private static final String CONNECTION_EXCEPTION = "08";

    /**
     * Cutover-shaped slots, in SQL. {@code s_} + a silo name, which {@code TenantSchemas} guarantees
     * is {@code t_} + 32 lowercase hex — so this matches the slots THIS machinery creates and nothing
     * else on the database. Tablesync's transient {@code pg_%_sync_%} slots are deliberately not
     * matched: they belong to a live subscription and clean themselves up.
     */
    private static final String CUTOVER_SHAPED_SLOT = "^s_t_[0-9a-f]{32}$";

    private final JdbcTemplate side;

    /** Which database this instance speaks to, for log lines only — never a conninfo. */
    private final String sideName;

    TenantReplication(JdbcTemplate side, String sideName) {
        this.side = side;
        this.sideName = sideName;
    }

    static String publicationName(String schema) {
        return "p_" + TenantSchemas.requireSiloSchema(schema);
    }

    /** Also the slot's name on the publisher: CREATE SUBSCRIPTION defaults slot_name to subname. */
    static String subscriptionName(String schema) {
        return "s_" + TenantSchemas.requireSiloSchema(schema);
    }

    /**
     * Schema-level, no row filter — ADR 0011 §7.1's decided shape. A row-filtered publication under
     * default PK replica identity breaks every tenant's UPDATE and DELETE at publication-creation
     * time (measured: the ERROR fires at DML time, fleet-wide, before any byte has moved), which is
     * why only a silo may cut over and the filter question never arises.
     */
    void createPublication(String schema) {
        String silo = TenantSchemas.requireSiloSchema(schema);
        side.execute("create publication " + quote(publicationName(silo))
                + " for tables in schema " + quote(silo));
        log.info("publication {} created on {} for schema {}", publicationName(silo), sideName, silo);
    }

    void dropPublication(String schema) {
        side.execute("drop publication if exists " + quote(publicationName(schema)));
    }

    boolean publicationExists(String schema) {
        Boolean exists = side.queryForObject(
                "select exists (select 1 from pg_publication where pubname = ?)",
                Boolean.class, publicationName(schema));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * @param copyData {@code true} for the forward stream (tablesync the whole tier), {@code false}
     *     for the reverse one — measured: a {@code copy_data = true} reversal into the origin that
     *     still holds every row storms {@code duplicate key} on every synced table's PK, because a
     *     tablesync into a populated table is a collision by construction (ADR 0011 §7.2 step 9)
     */
    void createSubscription(String schema, String conninfo, String publication, boolean copyData) {
        String name = subscriptionName(schema);
        // The conninfo is a SQL string literal (it cannot be bound in CREATE SUBSCRIPTION), so the
        // one escape that matters is the quote. It routinely carries a password: it must never reach
        // a log line or an exception message, which is why failures here are rethrown untouched —
        // Postgres' own error text does not echo the connection string.
        side.execute("create subscription " + quote(name)
                + " connection '" + conninfo.replace("'", "''") + "'"
                + " publication " + quote(publication)
                + " with (copy_data = " + copyData + ")");
        log.info("subscription {} created on {} (copy_data={})", name, sideName, copyData);
    }

    boolean subscriptionExists(String schema) {
        Boolean exists = side.queryForObject(
                "select exists (select 1 from pg_subscription where subname = ?)",
                Boolean.class, subscriptionName(schema));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Drops a subscription whole — slot included — taking the orphaning escape for the ONE failure it
     * was measured against, and never for any other.
     *
     * <p>The plain {@code DROP SUBSCRIPTION} dials the publisher to drop the slot. The escape is the
     * one the server's own HINT names: {@code DISABLE} → {@code SET (slot_name = NONE)} →
     * {@code DROP}, and it is <strong>not free</strong>: the slot it disassociates keeps retaining WAL
     * on the publisher until a human drops it, bounded only by {@code max_slot_wal_keep_size} — a
     * disk-full incident on a timer (ADR 0011 §7.2 step 10).
     *
     * <h2>Which failures buy that, measured on 18.4</h2>
     *
     * <p>The escape is worth an orphaned slot only when the publisher genuinely cannot be asked, and
     * that failure names itself in the wire protocol — <strong>SQLSTATE class {@code 08}</strong>
     * (connection exception). Both escapable shapes were executed against two scratch containers:
     *
     * <ul>
     *   <li>{@code 08006 — could not connect to publisher when attempting to drop replication slot
     *       "s_t_…": … Host is unreachable}. The escape applies; the slot survives on the publisher,
     *       inactive, {@code wal_status=reserved}. <em>This one orphans.</em></li>
     *   <li>{@code 08006 — could not drop replication slot "s_t_…" on publisher: ERROR: replication
     *       slot "s_t_…" does not exist}. The publisher ANSWERED; the slot is already gone. The escape
     *       applies and orphans nothing.</li>
     * </ul>
     *
     * <p>Everything else is rethrown, because paying an orphaned slot for a failure the escape does
     * not repair is a WAL leak bought with nothing. Measured non-escapable shapes:
     * {@code 25001 — DROP SUBSCRIPTION cannot run inside a transaction block} (the escape's first
     * {@code ALTER} then fails {@code 25P02} and the caller is told "current transaction is aborted"
     * instead of the truth), and {@code 55006 — replication slot "…" is active}. The old blanket
     * {@code catch (RuntimeException)} treated all of these as "publisher unreachable" at five call
     * sites, including the happy path inside the freeze where the publisher is the platform database
     * this very method has just drained.
     *
     * @return the slot left ORPHANED on the publisher, or empty when nothing was left behind. Never
     *     only a log line: every caller either puts it in {@code CutoverReport.warnings} or attaches
     *     it to what it throws, because the log is not where the runbook sends the operator
     */
    Optional<String> dropSubscriptionAndSlot(String schema, String publisherSideName) {
        String name = subscriptionName(schema);
        if (!subscriptionExists(schema)) {
            return Optional.empty();
        }
        try {
            side.execute("drop subscription " + quote(name));
            return Optional.empty();
        } catch (RuntimeException failure) {
            String state = sqlState(failure);
            if (state == null || !state.startsWith(CONNECTION_EXCEPTION)) {
                // Not the failure the escape repairs. Rethrown WITH the slot still attached, which is
                // the state a retry can finish cleanly — where the escape would have left a WAL-pinning
                // orphan behind a failure that had nothing to do with the publisher.
                throw failure;
            }
            log.warn("subscription {} on {} could not drop its slot on {} (SQLSTATE {}) — taking the"
                    + " DISABLE / slot_name=NONE escape: {}", name, sideName, publisherSideName, state,
                    failure.getMessage());
            side.execute("alter subscription " + quote(name) + " disable");
            side.execute("alter subscription " + quote(name) + " set (slot_name = NONE)");
            side.execute("drop subscription " + quote(name));
            if (theSlotWasAlreadyGone(failure)) {
                log.info("subscription {} was dropped detached from a slot that no longer existed on"
                        + " {} — nothing is pinning WAL there", name, publisherSideName);
                return Optional.empty();
            }
            log.error("subscription {} was dropped DETACHED from its slot: replication slot '{}' is"
                    + " now ORPHANED on {} and is pinning WAL there until someone runs"
                    + " select pg_drop_replication_slot('{}') on that database — or"
                    + " TenantCutover.reclaimAbandonedSlots('…'), which is the same drop with the"
                    + " in-flight cutovers subtracted (docs/runbooks/tenant-cutover.md, decommission)",
                    name, name, publisherSideName, name);
            return Optional.of(name);
        }
    }

    /** Relations of this side's subscription not yet at state {@code r} (ready). 0 = initial sync done. */
    int unsyncedRelations(String schema) {
        Integer pending = side.queryForObject("""
                select count(*) from pg_subscription_rel r
                  join pg_subscription s on s.oid = r.srsubid
                 where s.subname = ? and r.srsubstate <> 'r'
                """, Integer.class, subscriptionName(schema));
        return pending == null ? 0 : pending;
    }

    /**
     * The write head, read ONCE — the wait below compares against this captured value, never against
     * the live head, because the walsender decodes the WHOLE WAL stream: on a busy fleet the head is
     * moved by every other tenant's writes and a wait for equality with it can starve indefinitely,
     * while the captured value is a fixed point the slot provably crosses (measured — the head raced
     * past the captured LSN under unrelated-schema traffic while the wait converged).
     */
    String captureLsn() {
        return side.queryForObject("select pg_current_wal_lsn()::text", String.class);
    }

    /**
     * Whether this side's slot for {@code schema}'s subscription has confirmed past {@code lsn}.
     * Asked of the PUBLISHER — the slot lives where the WAL is.
     *
     * @throws TenantCutoverException if the slot is not there: a missing slot does not mean "caught
     *     up", it means the stream this wait is about does not exist
     */
    boolean confirmedPast(String schema, String lsn) {
        String slot = subscriptionName(schema);
        List<Boolean> answers = side.query(
                "select confirmed_flush_lsn >= ?::pg_lsn from pg_replication_slots where slot_name = ?",
                (rs, row) -> rs.getBoolean(1), lsn, slot);
        if (answers.isEmpty()) {
            throw new TenantCutoverException("replication slot '" + slot + "' does not exist on "
                    + sideName + ", so there is no stream to wait for. The subscription on the other"
                    + " side is gone or was never created — nothing has been flipped.");
        }
        return Boolean.TRUE.equals(answers.get(0));
    }

    /**
     * Cutover-shaped slots on this side that no in-flight cutover explains — each one retaining WAL
     * for a consumer that is never coming back. Reported at ERROR because the failure it precedes
     * (WAL retention until {@code max_slot_wal_keep_size} invalidates the slot, or the disk fills) is
     * on a timer, not hypothetical. Tablesync's transient {@code pg_%_sync_%} slots are deliberately
     * not matched: they belong to a live subscription and clean themselves up.
     */
    List<String> abandonedSlots(List<String> schemasWithLiveCutovers) {
        List<String> expected = schemasWithLiveCutovers.stream()
                .map(TenantReplication::subscriptionName)
                .toList();
        List<String> orphans = side.query("""
                select slot_name from pg_replication_slots
                 where slot_name ~ ? and not active
                 order by slot_name
                """, (rs, row) -> rs.getString(1), CUTOVER_SHAPED_SLOT)
                .stream()
                .filter(slot -> !expected.contains(slot))
                .toList();
        for (String orphan : orphans) {
            log.error("replication slot '{}' on {} is inactive and matches no in-flight cutover — it"
                    + " is pinning WAL and must be dropped: TenantCutover.reclaimAbandonedSlots on"
                    + " this datasource, or select pg_drop_replication_slot('{}') by hand",
                    orphan, sideName, orphan);
        }
        return orphans;
    }

    /**
     * Drops what {@link #abandonedSlots} named — the reclaim half of the detector, and the reason the
     * detector stopped being advice. Three shipped paths can leave a slot that no other call can
     * remove: a {@link #dropSubscriptionAndSlot} escape, a decommission that died after taking one,
     * and a {@code CREATE SUBSCRIPTION} that failed after its slot was created on the publisher.
     * Until this existed, every one of them ended at a log line and a human with psql, while the WAL
     * accrued.
     *
     * <p><strong>Why this is safe to run against the database a tenant is being SERVED from</strong>
     * — which {@code reclaimAbandonedCopy} deliberately refuses to do, and the difference is what is
     * being dropped. That call destroys a schema full of rows; this one drops a slot, which holds no
     * data and whose only effect is retention. The two filters make it unable to touch a live stream:
     * a slot an apply worker is attached to is {@code active} (and Postgres refuses to drop it at all
     * — measured, {@code 55006}), and a slot belonging to any in-flight cutover is subtracted by name
     * whatever its activity, so a WATCHING tenant's reverse stream is out of reach even during the
     * seconds its worker spends reconnecting.
     *
     * @return the slots actually dropped — empty is the answer on a healthy database
     */
    List<String> dropAbandonedSlots(List<String> schemasWithLiveCutovers) {
        List<String> dropped = new java.util.ArrayList<>();
        for (String orphan : abandonedSlots(schemasWithLiveCutovers)) {
            // The predicate is repeated INSIDE the drop so a slot that became active between the
            // detection and this statement is skipped rather than throwing 55006 and abandoning the
            // rest of the list. Nothing matched = nothing dropped, which is the correct answer.
            // The drop sits in the SELECT LIST and the name in the WHERE — reclaimAbandonedCopy's
            // idiom, and the only order in which the function cannot run for a row the filter excludes.
            List<String> gone = side.query("select pg_drop_replication_slot(slot_name)::text"
                            + " from pg_replication_slots where slot_name = ? and not active",
                    (rs, row) -> rs.getString(1), orphan);
            if (!gone.isEmpty()) {
                dropped.add(orphan);
                log.warn("dropped abandoned replication slot '{}' on {} — it was pinning WAL for a"
                        + " consumer that is never coming back", orphan, sideName);
            }
        }
        return List.copyOf(dropped);
    }

    /**
     * The SQLSTATE of the database failure inside a Spring {@code DataAccessException}, or null when
     * the chain holds no {@link SQLException} at all — which is itself an answer: a failure with no
     * SQLSTATE did not come from the server, so it cannot be the connection failure the escape is for.
     */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (cause.getCause() == cause) {
                return null;
            }
        }
        return null;
    }

    /**
     * Distinguishes the two measured {@code 08006}s: a publisher that could not be reached (a slot
     * survives there) from one that answered "that slot does not exist" (nothing survives). Matched on
     * the server's own text because SQLSTATE does not separate them; over-reporting is the safe
     * direction, and it costs the operator one reclaim that finds nothing.
     */
    private static boolean theSlotWasAlreadyGone(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("does not exist")) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /** Postgres identifier quoting; every name through here is derived from a validated silo name. */
    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
