package ug.co.smsone.audit.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
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
 * Read-only audit queries. {@code GET /api/v1/audit} is the platform-wide view (investigating is the
 * support job, so {@code platform-support} is the floor); {@code GET
 * /api/v1/orgs/{orgId}/audit} is scoped to one org and gated by the {@code audit:read} permission —
 * so an org's own admins can review their trail without platform access. Both are cursor-paginated
 * (newest first) and filterable by {@code action} and a {@code from}/{@code to} ISO-instant window.
 */
@RestController
class AuditController {

    private static final String RESOURCE_TYPE = "audit-entry";

    private final AuditQueryService audit;

    AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    /**
     * {@code actorPersonId} is the accountable human, and null when none is — a job, or a machine key.
     * {@code onBehalfOfPersonId} is non-null only for a row written inside an impersonation session, and
     * then names the identity the actor was wearing; {@code impersonationId} points at the session that
     * carries the stated reason.
     *
     * <p>Both id fields are rendered as strings because every id on this wire is, and {@code target}
     * stays free text: it is polymorphic (V13), so a row's target may be a person id, a role code or a
     * setting key depending on {@code action}.
     */
    record AuditAttributes(String action, String actorPersonId, String onBehalfOfPersonId,
            String impersonationId, String orgId, String target, String fromState, String toState,
            Instant occurredAt, Instant recordedAt) {
    }

    @GetMapping("/api/v1/audit")
    @Operation(summary = "Search the platform-wide audit trail",
            description = """
                    Newest first, narrowed by any combination of `org`, `action` and an ISO-8601 \
                    `from`/`to` instant window. For a row written inside an impersonation session \
                    `actorPersonId` is the accountable operator and `onBehalfOfPersonId` is the \
                    identity they wore.""")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> platform(
            @RequestParam(required = false) UUID org,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            CursorPageRequest page) {
        return query(org, action, from, to, page);
    }

    @GetMapping("/api/v1/orgs/{orgId}/audit")
    @Operation(summary = "Search one organization's audit trail",
            description = """
                    Newest first, narrowed by `action` and an ISO-8601 `from`/`to` instant window. \
                    For a row written inside an impersonation session `actorPersonId` is the \
                    accountable operator and `onBehalfOfPersonId` is the identity they wore.""")
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
        return WindowedResult.of(
                audit.query(orgId, action, parseInstant(from, "from"), parseInstant(to, "to"), page),
                page, AuditController::toResource);
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
                        asString(entry.getActorPersonId()),
                        asString(entry.getOnBehalfOfPersonId()),
                        asString(entry.getImpersonationId()),
                        asString(entry.getOrgId()),
                        entry.getTarget(),
                        entry.getFromState(),
                        entry.getToState(),
                        entry.getOccurredAt(),
                        entry.getCreatedAt()));
    }

    private static String asString(UUID id) {
        return id == null ? null : id.toString();
    }
}
