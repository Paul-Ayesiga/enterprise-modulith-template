package ug.co.smsone.audit.internal;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * The query side of the audit trail, split from the controller so the read runs inside a
 * {@code readOnly} service transaction (§3.1/§4.3) — the controller keeps only parameter parsing
 * and resource mapping.
 */
@Service
@Transactional(readOnly = true)
class AuditQueryService {

    /**
     * Ordered by {@code occurredAt}, which is the column the {@code from}/{@code to} filter below also
     * ranges on — and that agreement is the point. It used to keyset on {@code createdAt} while
     * filtering on {@code occurredAt}, so no single index could serve both halves: a windowed query
     * walked {@code idx_audit_org_created} from the newest row and threw away everything outside the
     * range, which for a narrow window far in the past means reading the org's whole history to fill
     * one page. The two timestamps track each other closely in practice ({@code created_at} is
     * {@code @CreatedDate}, {@code occurred_at} is passed to {@code AuditEntry.of}), which is exactly
     * why the mismatch looked fine in testing and only bites on real volume.
     *
     * <p>{@code occurredAt} is also the more honest key for an audit trail: callers asked when the thing
     * HAPPENED, not when the row was written. {@code id desc} remains the tiebreaker that makes the
     * keyset total. V49 adds the matching {@code (org_id, occurred_at desc, id desc)} index.
     */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    private final AuditEntryRepository entries;

    AuditQueryService(AuditEntryRepository entries) {
        this.entries = entries;
    }

    Window<AuditEntry> query(UUID orgId, String action, Instant from, Instant to, CursorPageRequest page) {
        Specification<AuditEntry> spec = filter(orgId, action, from, to);
        // scrollPosition(SORT), never the bare overload: a cursor minted for another collection carries
        // key names this keyset query cannot resolve, and unchecked it fails inside Spring Data as a 500
        // instead of the 422 the caller can act on.
        return entries.findBy(spec,
                q -> q.limit(page.size()).sortBy(NEWEST_FIRST).scroll(page.scrollPosition(NEWEST_FIRST)));
    }

    private static Specification<AuditEntry> filter(UUID orgId, String action, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), to));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
