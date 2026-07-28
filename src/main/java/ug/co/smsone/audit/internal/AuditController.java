package ug.co.smsone.audit.internal;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Read-only audit queries. {@code GET /api/v1/audit} is the platform-wide view (ADMIN); {@code GET
 * /api/v1/orgs/{orgId}/audit} is scoped to one org and gated by the {@code audit:read} permission —
 * so an org's own admins can review their trail without platform access. Both are cursor-paginated
 * (newest first) and filterable by {@code action} and an {@code occurredFrom}/{@code occurredTo} window.
 */
@RestController
class AuditController {

    private static final String RESOURCE_TYPE = "audit-entry";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AuditEntryRepository entries;

    AuditController(AuditEntryRepository entries) {
        this.entries = entries;
    }

    record AuditAttributes(String action, String orgId, String target, String actor, String detail,
            Instant occurredAt, Instant recordedAt) {
    }

    @GetMapping("/api/v1/audit")
    @PreAuthorize("hasRole('ADMIN')")
    WindowedResult<ResourceObject> platform(
            @RequestParam(required = false) UUID org,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            CursorPageRequest page) {
        return query(org, action, from, to, page);
    }

    @GetMapping("/api/v1/orgs/{orgId}/audit")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'audit:read')")
    WindowedResult<ResourceObject> forOrg(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            CursorPageRequest page) {
        return query(orgId, action, from, to, page);
    }

    private WindowedResult<ResourceObject> query(UUID orgId, String action, String from, String to,
            CursorPageRequest page) {
        Specification<AuditEntry> spec = filter(orgId, action,
                parseInstant(from, "from"), parseInstant(to, "to"));
        Window<AuditEntry> window = entries.findBy(spec,
                q -> q.limit(page.size()).sortBy(NEWEST_FIRST).scroll(page.scrollPosition()));
        return WindowedResult.of(window, page, AuditController::toResource);
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

    private static Instant parseInstant(String value, String parameter) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ValidationException("'" + parameter + "' must be an ISO-8601 instant (e.g. 2026-07-28T10:00:00Z).",
                    ApiSource.parameter(parameter));
        }
    }

    private static ResourceObject toResource(AuditEntry entry) {
        return new ResourceObject(entry.getId().toString(), RESOURCE_TYPE,
                new AuditAttributes(
                        entry.getAction(),
                        entry.getOrgId() == null ? null : entry.getOrgId().toString(),
                        entry.getTarget(),
                        entry.getActor(),
                        entry.getDetail(),
                        entry.getOccurredAt(),
                        entry.getCreatedAt()));
    }
}
