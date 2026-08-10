package ug.co.smsone.support.internal;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHome;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The platform support desk's cross-tenant reach, once tenants stopped sharing one schema
 * (ADR 0010 §5.1, Phase 5).
 *
 * <p>Every route under {@code /api/v1/admin/tickets} used to pin the pooled axis and read one schema,
 * because one schema held every ticket. After a promotion that same code answers an operator's queue
 * with the promoted tenant's tickets missing and its single-ticket routes with a 404 — no error, no log
 * line, just a customer whose escalated ticket the support desk cannot see. This class is what makes
 * both reach every home.
 *
 * <p>A separate bean and not a method on {@code SupportService}, for the reason AGENTS §4.3 states:
 * the axis has to be pinned <em>before</em> the transaction opens, so the loop must call
 * {@code SupportService}'s {@code @Transactional} reads THROUGH the proxy. A self-invocation from
 * inside that class would bypass the advice and every branch would run in the caller's transaction on
 * the caller's connection — which is to say on one schema, which is the bug.
 *
 * <h2>The queue is no longer a merge — §5.1's trigger fired (V61)</h2>
 *
 * <p>Phase 5 answered {@link #queue} with an N-way merge: the same keyset query once per home, merged
 * here. ADR 0010 §8 Q1 then measured it and the number is why this class no longer does that.
 * <strong>1.29–1.39 ms per home, flat from 100 homes upward — 279 ms per operator page at 200 homes,
 * linear in the home count</strong>, which is 2× §5.1's own "acceptable for an operator listing" budget
 * on an interactive surface. §5.1 put the trigger for replacing the merge with
 * {@code platform.ticket_index} at 50 silos; commit 0822943 then made {@code silo-per-org} the default
 * placement, so the home count IS the organization count and the trigger is not a future event.
 *
 * <p>{@link #queue} is now one keyset statement against {@link TicketIndex}, whose cost is a function of
 * the PAGE and not of the fleet. Three things the merge had to get right stop existing rather than
 * getting easier, and that is most of the value:
 *
 * <ul>
 *   <li>The cursor is minted by one branch and consumed by one branch, so there is no reconciling of
 *       {@code positionAt} across windows.</li>
 *   <li>The ordering is evaluated by Postgres, so the merge's most dangerous piece of code is deleted
 *       rather than kept: {@code UUID.compareTo} compares the two 64-bit halves as SIGNED longs while
 *       Postgres' {@code uuid_cmp} is an unsigned {@code memcmp}, so the two disagree on roughly half of
 *       all pairs, and the merge needed a hand-written unsigned comparator to pick the same boundary row
 *       Postgres would. Getting that wrong skipped or repeated a row only when two {@code created_at}
 *       values collided — the shape of defect that survives a test suite. A keyset predicate the database
 *       evaluates cannot have it.</li>
 *   <li>{@code hasNext} is the page's own {@code size + 1} probe rather than a disjunction over every
 *       branch's own {@code hasNext}.</li>
 * </ul>
 *
 * <p><strong>What is traded, stated rather than hidden:</strong> the queue is a projection, so it can be
 * momentarily behind a tenant's own schema, and — on a tenant served from another database (ADR 0011) —
 * behind it without a shared transaction at all. That is survivable only because of
 * {@link TicketIndex}'s invariant: the index RENDERS, the tenant schema ANSWERS. Every route below
 * {@link #queue} still reads the tenant's own row, so the worst a stale index can do is show an operator
 * a row whose status is a second old, or a ticket that 404s when they open it.
 *
 * <p><strong>{@link #onTicketsHome} still fans out, and deliberately.</strong> It uses the index as a
 * HINT — one primary-key lookup instead of up to (homes) probes — and falls back to the probe when the
 * hint is absent or wrong. Trusting it would turn an operator convenience into a routing authority and
 * let a stale projection 404 a live ticket, which is the one failure this whole class was written to
 * remove.
 */
@Component
class TicketFanOut {

    private final TicketRepository tickets;
    private final TicketIndex index;
    private final TenantFanOut fanOut;

    TicketFanOut(TicketRepository tickets, TicketIndex index, TenantFanOut fanOut) {
        this.tickets = tickets;
        this.index = index;
        this.fanOut = fanOut;
    }

    /**
     * One page of the cross-tenant queue, read from {@code platform.ticket_index}.
     *
     * <p>The cursor's keys are unchanged from the merge this replaces — {@code createdAt} and
     * {@code id}, the property names the deleted {@code QUEUE_SORT} encoded — so a cursor an operator is
     * holding across the deploy still decodes and still means the same position; a cursor minted for
     * another collection is still the client's 422 rather than a 500 inside the keyset predicate. See
     * {@code TicketIndex.decode}.
     */
    <T> WindowedResult<T> queue(String status, CursorPageRequest page, Function<TicketIndex.Row, T> mapper) {
        return index.queue(status, page, mapper);
    }

    /**
     * Runs {@code work} pinned to the home that holds {@code ticketId}.
     *
     * <p><strong>The index answers first, and it is only a hint.</strong> {@code platform.ticket_index}
     * knows which organization a ticket belongs to, so the common case is one primary-key lookup on
     * primary plus one pin — instead of up to (homes) probes, which under {@code silo-per-org} is up to
     * one probe per organization in the fleet for a ticket that happens to sort last.
     *
     * <p><strong>And when the hint is absent or wrong, the probe still runs</strong>, which is the whole
     * reason it is a hint. The index is a projection with two real ways to be behind the tenant — a
     * ticket opened before V61, and a cross-database write whose platform half has not landed (ADR 0011
     * §5.1) — and a projection allowed to 404 a live ticket has stopped being an operator convenience
     * and become a routing authority. The fallback costs one wasted lookup on a path that was already
     * doing (homes) of them.
     *
     * <p>The probe itself is unchanged: a probe per home, stopping at the first hit, pool first. That is
     * the price of a ticket id that carries no tenant in it; the alternative, threading an org id through
     * the operator's URL, would change a wire contract to work around a routing problem.
     *
     * @throws NotFoundException when no home holds it — the same 404 a single-schema read gave, so an
     *     operator cannot tell a deleted ticket from one this desk failed to route to
     */
    <T> T onTicketsHome(UUID ticketId, Supplier<T> work) {
        TenantFanOut.Fleet fleet = fanOut.fleet();
        // The hint is checked against the FLEET and not used raw: an organization this run must not
        // touch — frozen mid-promotion, half-provisioned, on a datasource this deployment has no pool
        // for — has no home, and pinning it anyway would be a write into a schema whose contents are
        // moving. `homeOf` refusing simply drops us into the probe below.
        Optional<TenantHome> hinted = index.homeOf(ticketId).flatMap(fleet::homeOf);
        // The exists check runs even when the hint is present, and it is not belt-and-braces: a hint
        // pointing at a home that no longer holds the ticket would otherwise run `work` there, and the
        // 404 it raised would look exactly like a ticket that never existed — with the probe below
        // never reached.
        if (hinted.isPresent() && holdsTicket(hinted.get(), ticketId)) {
            return TenantContext.callAs(hinted.get().axis(), work);
        }
        for (TenantHome home : fleet.homes()) {
            if (holdsTicket(home, ticketId)) {
                return TenantContext.callAs(home.axis(), work);
            }
        }
        throw new NotFoundException("Ticket not found.");
    }

    /**
     * {@code existsById} and not {@code findById}: the caller's work re-reads the ticket inside its own
     * transaction anyway, and handing a detached entity across a second pin is how a lazy association
     * ends up loading against the wrong schema.
     */
    private boolean holdsTicket(TenantHome home, UUID ticketId) {
        return Boolean.TRUE.equals(
                TenantContext.callAs(home.axis(), () -> tickets.existsById(ticketId)));
    }
}
