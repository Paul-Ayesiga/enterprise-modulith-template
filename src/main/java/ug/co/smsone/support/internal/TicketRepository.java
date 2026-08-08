package ug.co.smsone.support.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ug.co.smsone.shared.web.CursorPageRequest;

interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    Optional<Ticket> findByIdAndOrgId(UUID id, UUID orgId);

    default Window<Ticket> pageByOrg(UUID orgId, CursorPageRequest page) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return findBy((root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                q -> q.limit(page.size()).sortBy(sort).scroll(page.scrollPosition(sort)));
    }

    /**
     * The cross-tenant queue's total order, {@code created_at desc, id desc}.
     *
     * <p>A constant since Phase 5 rather than a local, because it is no longer used in one place: this
     * query orders ONE home's rows by it, and {@code TicketFanOut} merges the homes' pages by the same
     * order in Java. Two definitions of a keyset sort that must agree are two definitions that will
     * eventually disagree, and the symptom would be a cursor that skips or repeats a row — so there is
     * one of them, and the comparator says in its javadoc that it is a copy.
     */
    Sort QUEUE_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /**
     * One home's page of the platform queue. Unqualified, so the connection's {@code search_path}
     * decides whose tickets these are — which is what makes {@code TicketFanOut} able to run it once per
     * home with no change to the query (ADR 0010 §5.1).
     */
    default Window<Ticket> pageForQueue(String status, CursorPageRequest page) {
        return findBy((root, query, cb) -> status == null ? cb.conjunction()
                        : cb.equal(root.get("status"), status),
                q -> q.limit(page.size()).sortBy(QUEUE_SORT).scroll(page.scrollPosition(QUEUE_SORT)));
    }

    /** Breach candidates: past resolution due, not escalated, not terminal. Row-locked, SKIP LOCKED. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select t from Ticket t where t.escalated = false and t.resolutionDueAt <= :now "
            + "and t.status not in ('RESOLVED', 'CLOSED')")
    List<Ticket> lockBreached(@Param("now") Instant now, Limit limit);
}
