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

    // `pageForQueue` and its QUEUE_SORT constant are gone with V61. The cross-tenant operator queue is
    // no longer (one query per home) merged in Java — ADR 0010 §8 Q1 measured that merge at 279 ms per
    // page at 200 homes, linear in the fleet — it is one keyset statement against
    // platform.ticket_index. Its cursor deliberately kept THESE key names, `createdAt` and `id`, so a
    // cursor minted before the change still decodes; see TicketIndex.decode. What must not come back is
    // a second per-home definition of the operator's collection, because two definitions of one
    // collection's sort will eventually disagree and the symptom is a page that skips or repeats a row.

    /** Breach candidates: past resolution due, not escalated, not terminal. Row-locked, SKIP LOCKED. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select t from Ticket t where t.escalated = false and t.resolutionDueAt <= :now "
            + "and t.status not in ('RESOLVED', 'CLOSED')")
    List<Ticket> lockBreached(@Param("now") Instant now, Limit limit);
}
