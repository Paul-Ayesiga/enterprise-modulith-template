package ug.co.smsone.support.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.tenancy.CrossDatabaseWrites;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.Cursors;
import ug.co.smsone.shared.web.PageMeta;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * {@code platform.ticket_index} — the cross-tenant OPERATOR queue, kept in the PLATFORM schema because
 * {@code ticket} is not (ADR 0010 §5.1, §8 Q2; V61).
 *
 * <p><b>Why it has to exist.</b> Every read of {@code ticket} is single-tenant with exactly one
 * exception: {@code GET /api/v1/admin/tickets}, which asks a question no tenant schema can answer
 * because the answer spans tenants and the operator has not chosen one. Phase 5 answered it with a
 * merge of one keyset query per home, and ADR 0010 §8 Q1 then measured that merge: <b>1.29–1.39 ms per
 * home, flat from 100 homes upward — 279 ms per queue page at 200 homes, linear in the tenant count, on
 * an interactive surface.</b> Since commit 0822943 made {@code silo-per-org} the default placement, the
 * home count IS the organization count, so the linear term is the tenant base. Through this table one
 * page is one indexed keyset statement whose cost has nothing to do with how many tenants exist.
 *
 * <p><b>The invariant, and it is {@code OrgMembershipIndex}'s with one word changed: the index RENDERS,
 * the tenant schema ANSWERS.</b> A tenant reading its own tickets never touches this table; the
 * operator's single-ticket routes ({@code GET/POST …/admin/tickets/{id}…}) read the tenant's own row;
 * no authorization decision may read it. The one thing it is allowed to be believed about is WHICH HOME
 * to look in first — {@link #homeOf} is a hint that {@link TicketFanOut#onTicketsHome} falls back from,
 * never a routing authority.
 *
 * <p><b>What happens when the two disagree — and they can.</b> On a deployment where the tenant is
 * co-located with primary they cannot drift on any path through this class: {@link #record} runs inside
 * the caller's transaction on the same connection and commits with the {@code ticket} row itself. They
 * drift on the paths that do not go through it, and those are worth naming out loud:
 *
 * <ul>
 *   <li><b>Index row with no live ticket</b> — the operator's queue lists a ticket; opening it reads the
 *       tenant and 404s. Ugly, not dangerous. <b>This is the direction that must stay harmless, and it
 *       is why the invariant above is the invariant.</b></li>
 *   <li><b>Live ticket with no index row</b> — the support desk never sees a customer's problem, and
 *       there is no other route to it. That is the Phase 5 failure this module already paid for once
 *       (a promoted tenant's tickets silently missing from the queue), and it is the one the reconciler
 *       mostly exists for.</li>
 * </ul>
 *
 * <p><b>The producers of drift, since ADR 0011 made the write cross-database.</b>
 * {@code SoftDeletePurgeJob} hard-deletes aged-out {@code ticket} rows in raw SQL — it must, because
 * {@code @SQLRestriction} hides them from JPA — and raw SQL has no idea this projection exists; nothing
 * in the shipped application soft-deletes a ticket at all, so <b>the reconciler is not a backstop for
 * the delete path, it IS the delete path</b>, which is {@code SoftDeletePurgeJob.sweepSearchResidue}'s
 * situation exactly; {@code SoftDeleteRecovery.restore} brings a row back in raw SQL; every ticket that
 * existed before this table did has no row here and no migration could have backfilled it (the platform
 * migration runs before any tenant schema is reachable, and after Phase 5 a backfill would have to enter
 * every silo); and test fixtures write {@code ticket} directly. {@link TicketIndexReconciler} is what
 * replaces the foreign key this table is forbidden to have.
 *
 * <p><b>For a tenant on ANOTHER database the pair is eventually consistent, and the ordering is chosen
 * rather than inherited.</b> {@link CrossDatabaseWrites#runOnPlatform} is the same connection and the
 * same transaction whenever the tenant's rows and this table are in one database — i.e. every tenant on
 * every deployment with no remote datasource configured, where the paragraph above holds unchanged. When
 * they are not, the upsert commits on primary <em>before</em> the ticket it mirrors, so the window that
 * is left open leaves an index row with no ticket (the benign direction) and never a ticket with no
 * index row. There is no cross-database atomic write and this platform refuses XA (ADR 0011 §5.1); the
 * honest design is to name which failure survives, not to pretend the pair is atomic.
 *
 * <p><b>Raw JDBC rather than an entity, deliberately</b> — {@code OrgMembershipIndex}'s reasoning
 * verbatim. An {@code @Entity} would put this projection in the persistence context, where a flush
 * ordering or a cascade could write it without the {@code ticket} row it mirrors, and it would tempt the
 * next reader to join it to {@code Ticket} in a query, which is the join that must never exist once the
 * two live in different databases.
 *
 * <p><b>Every statement below writes {@code platform.ticket_index} out in full, in the same string
 * literal as the keyword that introduces it.</b> Two rules, not taste. The qualification: this class is
 * reached from BOTH axes — {@link #record} pinned to the tenant that owns the ticket, {@link #queue} on
 * the operator's platform axis — and an unqualified name would resolve differently on each, to nothing
 * at all on the tenant one (ADR 0010 §3.1, AGENTS §1). The LITERAL:
 * {@code PlatformSchemaQualificationTest} scans source strings, so a table name concatenated onto its
 * keyword from a constant is invisible to the gate that enforces the first rule —
 * {@code SearchQueryService} keeps its {@code from} inside the fragment for the same reason.
 */
@Component
class TicketIndex {

    /**
     * The projection's columns, in one place, because three statements have to agree on them: the
     * upsert's insert list, the upsert's conflict comparison, and the queue's select list. A column
     * added to one and not the others is a column that is written and never read, or read and never
     * refreshed — and neither fails.
     */
    private static final String COLUMNS = "ticket_id, org_id, opener_person_id, subject, category,"
            + " priority, status, assignee_person_id, escalated, first_response_at, resolution_due_at,"
            + " created_at";

    /**
     * Upsert, with the {@code is distinct from} guard that makes an already-correct row cost no write at
     * all. That matters more than it looks: without it every status transition, every reply and every
     * reconciler pass would rewrite rows that had not changed, and a projection that almost never changes
     * churning nightly is a vacuum problem invented for nothing.
     *
     * <p>The comparison is written row-wise rather than column-by-column for one reason: a
     * hand-maintained conjunction of eleven {@code is distinct from} clauses is a list that will
     * eventually lose a column, and losing one means that column silently stops being repaired.
     */
    private static final String UPSERT = """
            insert into platform.ticket_index as i (%s) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (ticket_id) do update set
                org_id = excluded.org_id,
                opener_person_id = excluded.opener_person_id,
                subject = excluded.subject,
                category = excluded.category,
                priority = excluded.priority,
                status = excluded.status,
                assignee_person_id = excluded.assignee_person_id,
                escalated = excluded.escalated,
                first_response_at = excluded.first_response_at,
                resolution_due_at = excluded.resolution_due_at,
                created_at = excluded.created_at
            where (i.org_id, i.opener_person_id, i.subject, i.category, i.priority, i.status,
                   i.assignee_person_id, i.escalated, i.first_response_at, i.resolution_due_at,
                   i.created_at)
               is distinct from
                  (excluded.org_id, excluded.opener_person_id, excluded.subject, excluded.category,
                   excluded.priority, excluded.status, excluded.assignee_person_id, excluded.escalated,
                   excluded.first_response_at, excluded.resolution_due_at, excluded.created_at)
            """.formatted(COLUMNS);

    /** One page of the queue. The predicates and the limit are appended by {@link #queue}. */
    private static final String SELECT_PAGE = "select " + COLUMNS + " from platform.ticket_index";

    private final JdbcTemplate jdbc;
    private final CrossDatabaseWrites platformTier;

    TicketIndex(JdbcTemplate jdbc, CrossDatabaseWrites platformTier) {
        this.jdbc = jdbc;
        this.platformTier = platformTier;
    }

    /**
     * One row of the operator queue, as this table holds it. Deliberately not a {@link Ticket}: a
     * projection row and an aggregate are different things, and a type that could be mistaken for the
     * second is a type someone will eventually save.
     */
    record Row(UUID ticketId, UUID orgId, UUID openerPersonId, String subject, String category,
            String priority, String status, UUID assigneePersonId, boolean escalated,
            Instant firstResponseAt, Instant resolutionDueAt, Instant createdAt) {
    }

    /**
     * Package-private, and shared with {@link TicketIndexReconciler}: its refresh arm reads the SAME
     * twelve columns off a tenant's {@code ticket} table (aliasing {@code id as ticket_id}) and has to
     * land them in the same record. Two mappers over one row shape is one more place for a column to be
     * added to the projection and forgotten by the repair.
     */
    static final RowMapper<Row> ROW = (rs, index) -> new Row(
            rs.getObject("ticket_id", UUID.class),
            rs.getObject("org_id", UUID.class),
            rs.getObject("opener_person_id", UUID.class),
            rs.getString("subject"),
            rs.getString("category"),
            rs.getString("priority"),
            rs.getString("status"),
            rs.getObject("assignee_person_id", UUID.class),
            rs.getBoolean("escalated"),
            instant(rs.getTimestamp("first_response_at")),
            instant(rs.getTimestamp("resolution_due_at")),
            instant(rs.getTimestamp("created_at")));

    /**
     * Records (or refreshes) the queue row for a ticket.
     *
     * <p><b>Call it inside the transaction that wrote the {@code ticket} row</b>, and AFTER the save
     * that assigns the id and the created stamp. While the tenant is co-located with primary the two
     * statements then share one connection and commit together, which is the only reason the pair cannot
     * disagree at all; when it is not, see the class note for which direction is left open and why that
     * one.
     *
     * <p>Idempotent by upsert, so it is safe to call on every mutation rather than only on the ones that
     * changed an indexed column — and useful there, because that is what heals a ticket opened before
     * this table existed the first time anybody touches it.
     */
    void record(Ticket ticket) {
        platformTier.runOnPlatform(() -> jdbc.update(UPSERT,
                ticket.getId(), ticket.getOrgId(), ticket.getOpenerPersonId(), ticket.getSubject(),
                ticket.getCategory(), ticket.getPriority(), ticket.getStatus(),
                ticket.getAssigneePersonId(), ticket.isEscalated(),
                timestamp(ticket.getFirstResponseAt()), timestamp(ticket.getResolutionDueAt()),
                timestamp(ticket.getCreatedAt())));
    }

    /**
     * The reconciler's bulk arm: one batch of rows read off a tenant, upserted here.
     *
     * <p>Lives beside {@link #record} rather than in {@link TicketIndexReconciler} so that
     * {@link #UPSERT} has exactly one home — a reconciler carrying its own copy of the projection's
     * write statement is a copy that drifts from the write path it is supposed to agree with, and the
     * symptom is a column the live path maintains and the nightly repair quietly reverts.
     *
     * @return how many rows the batch actually CHANGED. The {@code is distinct from} guard makes an
     *     already-correct row a zero-row update, so this count is "how much drift was repaired" and not
     *     "how many tickets were examined" — which is the number worth logging loudly.
     */
    int recordAll(List<Row> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = rows.stream().map(row -> new Object[] {
                row.ticketId(), row.orgId(), row.openerPersonId(), row.subject(), row.category(),
                row.priority(), row.status(), row.assigneePersonId(), row.escalated(),
                timestamp(row.firstResponseAt()), timestamp(row.resolutionDueAt()),
                timestamp(row.createdAt())}).toList();
        int[] written = platformTier.callOnPlatform(() -> jdbc.batchUpdate(UPSERT, batch));
        int changed = 0;
        for (int rowsAffected : written) {
            changed += Math.max(rowsAffected, 0); // SUCCESS_NO_INFO is negative on some drivers
        }
        return changed;
    }

    /**
     * Drops queue rows for tickets that are no longer live — <b>the reconciler's arm, and the only
     * delete path this projection has</b>.
     *
     * <p>That is not an omission. Nothing in the shipped application soft-deletes a ticket, and the two
     * things that make one disappear are both raw SQL that cannot know this table exists:
     * {@code SoftDeletePurgeJob} hard-deleting an aged-out row, and a tenant schema dropped whole. A
     * per-call-site {@code forget} would therefore be a method with no caller pretending the delete path
     * was covered. {@code SoftDeletePurgeJob.sweepSearchResidue} exists for precisely this shape and ADR
     * 0010 §8 Q2 turns it into the rule: no projection ships without its reconciler.
     */
    int forget(List<UUID> ticketIds) {
        if (ticketIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ticketIds.size(), "?"));
        return platformTier.callOnPlatform(() -> jdbc.update(
                "delete from platform.ticket_index where ticket_id in (" + placeholders + ")",
                ticketIds.toArray()));
    }

    /**
     * One page of the cross-tenant operator queue, newest first, optionally narrowed to one status.
     *
     * <p><b>The cursor is byte-compatible with the merge this replaces.</b> Its keys are still
     * {@code createdAt} and {@code id} in that order, because {@code TicketRepository.QUEUE_SORT}'s
     * property names are what the previous implementation encoded — so a cursor an operator is holding
     * across the deploy still decodes and still means the same position. Changing the key names would
     * have turned every in-flight page into a 422 for no gain.
     *
     * <p>Hand-rolled keyset rather than Spring Data's, on {@code ExchangeJobStore.list}'s precedent: this
     * table has no entity by design (see the class note), and {@code (created_at, ticket_id) < (?, ?)} is
     * the same row-wise comparison Spring Data would emit — evaluated by Postgres, which is what makes
     * the ordering Postgres' own rather than Java's. That is the same trap {@code TicketFanOut}'s
     * comparator had to document: {@code UUID.compareTo} is signed and {@code uuid_cmp} is not, so the
     * two disagree on about half of all pairs. Here the comparison never leaves the database, so the
     * trap does not exist — which is one of the quieter things this table buys.
     *
     * <p>Borrowed on the platform axis: {@code CurrentUserFilter} pins whatever organization the
     * caller's credential names on EVERY route, not only under {@code /orgs/**} (ADR 0011 §5.1), so an
     * operator whose token happens to name one would otherwise issue this read on that tenant's
     * connection — which for a remote tenant holds no {@code platform} schema worth the name.
     */
    <T> WindowedResult<T> queue(String status, CursorPageRequest page, Function<Row, T> mapper) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        Cursor cursor = decode(page);
        StringBuilder sql = new StringBuilder(SELECT_PAGE);
        List<Object> params = new ArrayList<>();
        String join = " where ";
        if (normalized != null) {
            sql.append(join).append("status = ?");
            join = " and ";
            params.add(normalized);
        }
        if (cursor != null) {
            sql.append(join).append("(created_at, ticket_id) < (?, ?)");
            params.add(Timestamp.from(cursor.createdAt()));
            params.add(cursor.ticketId());
        }
        // size + 1 is how hasMore is answered without a COUNT, which ADR 0002 forbids and keyset
        // pagination does not need.
        sql.append(" order by created_at desc, ticket_id desc limit ?");
        params.add(page.size() + 1);

        List<Row> rows = platformTier.callOnPlatform(
                () -> jdbc.query(sql.toString(), ROW, params.toArray()));
        boolean hasMore = rows.size() > page.size();
        List<Row> pageRows = hasMore ? rows.subList(0, page.size()) : rows;
        String next = null;
        if (hasMore) {
            Row last = pageRows.getLast();
            Map<String, Object> keys = new LinkedHashMap<>();
            keys.put("createdAt", last.createdAt());
            keys.put("id", last.ticketId());
            next = Cursors.encode(ScrollPosition.forward(keys));
        }
        List<T> items = pageRows.stream().map(mapper).toList();
        return new WindowedResult<>(items, new PageMeta(page.size(), items.size(), hasMore, next));
    }

    /**
     * Which organization the index believes holds this ticket — <b>a hint, never an answer</b>.
     *
     * <p>A ticket id carries no tenant, so {@link TicketFanOut#onTicketsHome} has always had to probe
     * the homes one at a time. This turns the common case into a single primary-key lookup and leaves
     * the probe as the fallback, which is the only shape that keeps the class invariant intact: if this
     * table is stale, missing or ahead of the tenant, the caller still finds the ticket — one probe
     * slower. Trusting it would make an operator convenience into a routing authority, and a projection
     * that can 404 a live ticket is a projection that has stopped being a convenience.
     */
    Optional<UUID> homeOf(UUID ticketId) {
        return platformTier.callOnPlatform(() -> jdbc.query(
                        "select org_id from platform.ticket_index where ticket_id = ?",
                        (rs, index) -> rs.getObject("org_id", UUID.class), ticketId)
                .stream().findFirst());
    }

    /** The keyset position, in this collection's own vocabulary. */
    private record Cursor(Instant createdAt, UUID ticketId) {
    }

    /**
     * A cursor minted for another collection decodes syntactically and then means nothing here, so it is
     * rejected as the client's 422 rather than blowing up as a 500 inside the keyset predicate — the
     * same check {@code CursorPageRequest.scrollPosition(Sort)} performs for the Spring Data collections
     * and {@code ExchangeJobStore.decode} performs for the other hand-rolled one.
     */
    private static Cursor decode(CursorPageRequest page) {
        KeysetScrollPosition position = page.scrollPosition();
        Map<String, Object> keys = position.getKeys();
        if (keys.isEmpty()) {
            return null;
        }
        if (keys.size() != 2 || !(keys.get("createdAt") instanceof Instant createdAt)
                || !(keys.get("id") instanceof UUID ticketId)) {
            throw new ValidationException("page[after] is not a valid cursor for this collection.",
                    ApiSource.parameter("page[after]"));
        }
        return new Cursor(createdAt, ticketId);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
