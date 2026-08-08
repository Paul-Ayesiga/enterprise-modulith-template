package ug.co.smsone.support.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHome;
import ug.co.smsone.shared.web.CursorPageRequest;

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
 * <h2>The queue merge, and why it is honest</h2>
 *
 * <p>ADR 0010 §5.1 measured the alternative — one {@code UNION ALL} branch per schema in a single
 * statement — at 0.5–0.66 ms of planning per branch that does not amortize, ~2 lock entries per branch
 * held for the whole transaction, and a query text that defeats both the JDBC statement cache and
 * Postgres' plan cache. {@link #queue} instead runs the SAME keyset query once per home and merges the
 * results here. Three properties make that a merge and not an approximation:
 *
 * <ol>
 *   <li><strong>Every branch is scrolled from the SAME cursor</strong> and sorted by the same total
 *       order, so each returns its own next {@code size} rows after that position. The merge of those
 *       lists, truncated to {@code size}, is exactly the global next {@code size} rows — that is the
 *       standard N-way merge, and it is exact rather than approximate because the sort key
 *       {@code (createdAt, id)} is unique across the whole fleet, not just within a home.</li>
 *   <li><strong>The next cursor is taken from the winning branch's own window</strong>
 *       ({@code positionAt}), never rebuilt here, so it is byte-identical to the cursor that branch
 *       would have minted on its own.</li>
 *   <li><strong>The tie-break comparison matches Postgres'</strong> — see
 *       {@link #compareUnsigned(UUID, UUID)}, which is the one place this could have gone subtly wrong.</li>
 * </ol>
 *
 * <p>The cost is (homes) round trips and (homes × size) rows fetched per page instead of one statement.
 * At the 200-silo ceiling that is the ~130 ms §5.1 budgets for an operator listing; §5.1's own trigger
 * for replacing this with {@code platform.ticket_index} is 50 silos, which is where
 * {@link TenantFanOut} starts warning.
 */
@Component
class TicketFanOut {

    /**
     * {@code created_at desc, id desc} — the merge's copy of
     * {@link TicketRepository#QUEUE_SORT}, which is what each branch ordered by.
     *
     * <p>The two definitions have to agree and nothing but this comment says so, which is the honest
     * statement of the cost of merging in Java: a sort declared to Spring Data cannot also be handed to
     * {@link Comparator}. {@code CursorPaginationContractTest}'s cursor round trip is what catches a
     * disagreement, because a page whose ordering differs from its branches' produces a cursor that
     * skips or repeats a row.
     */
    private static final Comparator<Candidate> QUEUE_ORDER =
            Comparator.comparing((Candidate candidate) -> candidate.ticket().getCreatedAt(),
                            Comparator.reverseOrder())
                    .thenComparing(candidate -> candidate.ticket().getId(),
                            (left, right) -> compareUnsigned(right, left));

    private final SupportService support;
    private final TicketRepository tickets;
    private final TenantFanOut fanOut;

    TicketFanOut(SupportService support, TicketRepository tickets, TenantFanOut fanOut) {
        this.support = support;
        this.tickets = tickets;
        this.fanOut = fanOut;
    }

    /**
     * One page of the cross-tenant queue, merged from every home.
     *
     * <p>The cursor is validated once per branch by {@code page.scrollPosition(SORT)} inside
     * {@link TicketRepository#pageForQueue}, which is the same validation a single-schema read did —
     * every branch sees the same cursor, so a cursor minted for another collection is a 422 from the
     * first branch rather than a 500 from the merge.
     */
    Window<Ticket> queue(String status, CursorPageRequest page) {
        List<Candidate> candidates = new ArrayList<>();
        boolean anyBranchHasMore = false;
        for (TenantHome home : fanOut.fleet().homes()) {
            Window<Ticket> branch = TenantContext.callAs(home.axis(), () -> support.queue(status, page));
            anyBranchHasMore |= branch.hasNext();
            for (int index = 0; index < branch.size(); index++) {
                candidates.add(new Candidate(branch.getContent().get(index), branch, index));
            }
        }
        candidates.sort(QUEUE_ORDER);

        int size = Math.min(page.size(), candidates.size());
        List<Ticket> content = new ArrayList<>(size);
        List<ScrollPosition> positions = new ArrayList<>(size);
        for (Candidate candidate : candidates.subList(0, size)) {
            content.add(candidate.ticket());
            // The branch's OWN position for that row. Rebuilding a keyset from the entity's fields would
            // work today and would silently drift the first time the collection's sort changed, because
            // nothing would compare the two definitions.
            positions.add(candidate.branch().positionAt(candidate.index()));
        }
        // More to come if this page truncated the merged set, OR if any branch had more of its own —
        // a branch that returned fewer than `size` rows can still be the one holding the next page's
        // oldest row, so both arms are needed and neither implies the other.
        boolean hasNext = candidates.size() > size || anyBranchHasMore;
        return Window.from(content, positions::get, hasNext);
    }

    /**
     * Runs {@code work} pinned to the home that holds {@code ticketId}.
     *
     * <p><strong>A probe per home, stopping at the first hit, and the pool is probed first</strong> —
     * which means the common case is one indexed primary-key lookup and the worst case is one per home.
     * That is the price of a ticket id that carries no tenant in it; the alternative, threading an org
     * id through the operator's URL, would change a wire contract to work around a routing problem.
     *
     * @throws NotFoundException when no home holds it — the same 404 a single-schema read gave, so an
     *     operator cannot tell a deleted ticket from one this desk failed to route to
     */
    <T> T onTicketsHome(UUID ticketId, Supplier<T> work) {
        for (TenantHome home : fanOut.fleet().homes()) {
            // existsById and not findById: the work below re-reads the ticket inside its own
            // transaction anyway, and handing a detached entity across a second pin is how a lazy
            // association ends up loading against the wrong schema.
            if (Boolean.TRUE.equals(TenantContext.callAs(home.axis(), () -> tickets.existsById(ticketId)))) {
                return TenantContext.callAs(home.axis(), work);
            }
        }
        throw new NotFoundException("Ticket not found.");
    }

    /** One branch's row, kept with the window it came from so its cursor can be taken from it. */
    private record Candidate(Ticket ticket, Window<Ticket> branch, int index) {
    }

    /**
     * Compares two UUIDs the way Postgres does, which is <strong>not</strong> the way
     * {@link UUID#compareTo} does.
     *
     * <p>{@code UUID.compareTo} compares the two 64-bit halves as SIGNED longs, so any uuid whose first
     * bit is set sorts below every uuid whose first bit is not — Java puts {@code ffff…} before
     * {@code 0000…}. Postgres' {@code uuid_cmp} is a {@code memcmp} over the sixteen bytes, i.e.
     * unsigned. Half of all randomly generated uuids have that bit set, so the two orders disagree on
     * roughly half of all pairs.
     *
     * <p>It matters here and only here: the keyset predicate each branch runs is evaluated by Postgres,
     * so the boundary row this merge picks as "the last of the page" must be the boundary Postgres
     * agrees with. Sorting with {@code compareTo} would put a different row last whenever two tickets
     * share a {@code created_at}, and the cursor minted from it would then skip or repeat rows on the
     * next page — intermittently, in proportion to how often timestamps collide, which is exactly the
     * kind of defect that survives a test suite.
     */
    private static int compareUnsigned(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    /** Exposed for the test that pins the merge's ordering to Postgres' own. */
    static int compareIdsAsPostgresWould(UUID left, UUID right) {
        return compareUnsigned(left, right);
    }

    /** Exposed for the test that pins the merge's ordering to Postgres' own. */
    static Comparator<Ticket> queueOrderForTest() {
        return Comparator.comparing(Ticket::getCreatedAt, Comparator.<Instant>reverseOrder())
                .thenComparing(Ticket::getId, (left, right) -> compareUnsigned(right, left));
    }
}
