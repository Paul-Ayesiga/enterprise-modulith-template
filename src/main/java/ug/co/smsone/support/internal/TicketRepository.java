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

    default Window<Ticket> pageForQueue(String status, CursorPageRequest page) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return findBy((root, query, cb) -> status == null ? cb.conjunction()
                        : cb.equal(root.get("status"), status),
                q -> q.limit(page.size()).sortBy(sort).scroll(page.scrollPosition(sort)));
    }

    /** Breach candidates: past resolution due, not escalated, not terminal. Row-locked, SKIP LOCKED. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select t from Ticket t where t.escalated = false and t.resolutionDueAt <= :now "
            + "and t.status not in ('RESOLVED', 'CLOSED')")
    List<Ticket> lockBreached(@Param("now") Instant now, Limit limit);
}
