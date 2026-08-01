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

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

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
