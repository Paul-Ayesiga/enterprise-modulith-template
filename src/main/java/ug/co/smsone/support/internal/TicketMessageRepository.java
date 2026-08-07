package ug.co.smsone.support.internal;

import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ug.co.smsone.shared.web.CursorPageRequest;

interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID>,
        JpaSpecificationExecutor<TicketMessage> {

    /**
     * One ticket's conversation, oldest first, cursor-paged.
     *
     * <p>Two things are deliberately in the QUERY rather than in Java. The visibility cut is one: a
     * tenant read adds {@code internal = false} as a predicate, so rows the caller may not see are
     * never fetched — filtering them out after the fact still moved every internal note through the
     * connection, the persistence context and this process's heap, which is a disclosure risk one
     * refactor away from being a disclosure. The limit is the other: a ticket's message count is
     * unbounded (a long-running incident runs to hundreds), and "read the whole conversation" is the
     * kind of call that looks free until one ticket makes it not.
     *
     * <p>Sorted ASCENDING because a conversation reads oldest-first, which makes this the one keyset
     * collection here that pages FORWARD in time; {@code (created_at, id)} is covered by
     * {@code idx_ticket_message_ticket (ticket_id, created_at)}, so the scan is ordered, not sorted.
     */
    default Window<TicketMessage> pageByTicket(UUID ticketId, boolean includeInternal,
            CursorPageRequest page) {
        Sort sort = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
        return findBy((root, query, cb) -> includeInternal
                        ? cb.equal(root.get("ticketId"), ticketId)
                        : cb.and(cb.equal(root.get("ticketId"), ticketId),
                                cb.isFalse(root.get("internal"))),
                q -> q.limit(page.size()).sortBy(sort).scroll(page.scrollPosition(sort)));
    }
}
