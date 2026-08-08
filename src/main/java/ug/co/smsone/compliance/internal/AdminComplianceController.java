package ug.co.smsone.compliance.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The platform's compliance controls: place and release legal holds (which BLOCK the purge and any
 * erasure of the held person/org), and execute an erasure on a person's behalf. Placing holds is
 * platform-admin; listing is platform-support.
 *
 * <p>The paths keep the word {@code subject}, and that is not a leftover: in this module "subject" is
 * the GDPR data subject — a human with rights — not a token's {@code sub}. The stored discriminator
 * agrees ({@code legal_hold.scope = 'SUBJECT'}, V34). What changed is the PAYLOAD: every identity on
 * this wire is now a {@code person.id} uuid, because the columns behind them are.
 */
@RestController
@RequestMapping("/api/v1/admin/compliance")
class AdminComplianceController {

    /**
     * The REDACTED half of what used to be one "org export". The other half —
     * {@code TenantExtractionService}, the complete secret-bearing copy used to lift a tenant
     * elsewhere — has no route here and is not getting one: it is unbounded and carries live
     * credentials, so it is an operator runbook step, not a JSON response (ADR 0010 §6).
     */
    private final GdprBundleService gdprBundle;

    private final ComplianceService compliance;

    AdminComplianceController(ComplianceService compliance, GdprBundleService gdprBundle) {
        this.compliance = compliance;
        this.gdprBundle = gdprBundle;
    }

    record PersonHoldRequest(UUID personId, String reason) {
    }

    record OrgHoldRequest(UUID orgId, String reason) {
    }

    record ErasureRequestBody(UUID personId) {
    }

    @GetMapping("/legal-holds")
    @Operation(summary = "List active legal holds",
            description = "Newest first, cursor-paginated (`page[size]`, `page[after]`). Holds are "
                    + "never deleted — only released — so the active set has no natural ceiling and "
                    + "this collection has never been safe to return whole.")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> holds(CursorPageRequest page) {
        return WindowedResult.of(compliance.activeHolds(page), page,
                h -> new ResourceObject(h.getId().toString(), "legal-hold",
                        Map.of("scope", h.getScope(),
                                "personId", h.getPersonId() == null ? "" : h.getPersonId().toString(),
                                "orgId", h.getOrgId() == null ? "" : h.getOrgId().toString(),
                                "reason", h.getReason(), "placedAt", h.getPlacedAt())));
    }

    @PostMapping("/legal-holds/subject")
    @Operation(summary = "Place a legal hold on a person",
            description = "While active, the person's data cannot be purged or erased. "
                    + "`personId` is a person id — an account's id from the admin users surface.")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject holdPerson(@RequestBody PersonHoldRequest request, CurrentUser user) {
        UUID personId = required(request.personId(), "personId");
        var hold = compliance.placePersonHold(personId, request.reason(), accountablePerson(user));
        return new ResourceObject(hold.getId().toString(), "legal-hold",
                Map.of("scope", hold.getScope(), "personId", personId.toString()));
    }

    @PostMapping("/legal-holds/org")
    @Operation(summary = "Place a legal hold on an organization")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject holdOrg(@RequestBody OrgHoldRequest request, CurrentUser user) {
        UUID orgId = required(request.orgId(), "orgId");
        var hold = compliance.placeOrgHold(orgId, request.reason(), accountablePerson(user));
        return new ResourceObject(hold.getId().toString(), "legal-hold",
                Map.of("scope", hold.getScope(), "orgId", orgId.toString()));
    }

    @DeleteMapping("/legal-holds/{id}")
    @Operation(summary = "Release a legal hold")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID id, CurrentUser user) {
        compliance.releaseHold(id, accountablePerson(user));
    }

    @PostMapping("/erasure")
    @Operation(summary = "Execute an erasure on a person's behalf",
            description = "REFUSED while a legal hold is in force for the person.")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject erase(@RequestBody ErasureRequestBody request, CurrentUser user) {
        ErasureRequest erasure = compliance.requestErasure(
                required(request.personId(), "personId"), accountablePerson(user));
        return new ResourceObject(erasure.getId().toString(), "erasure-request",
                Map.of("status", erasure.getStatus(),
                        "detail", erasure.getDetail() == null ? "" : erasure.getDetail()));
    }

    @GetMapping("/orgs/{orgId}/export")
    @Operation(summary = "Export one organization's records (redacted disclosure bundle)",
            description = """
                    A REDACTED JSON bundle of the organization's rows across the disclosed tables — \
                    membership, roles, subscription, integrations, webhooks, payments, usage, tickets, \
                    audit. It is a disclosure artifact, NOT a tenant extraction: secret-bearing \
                    columns never leave the database, per-table row caps apply, and the tenant's own \
                    tables that carry no disclosure value are not here. Lifting an organization to \
                    another deployment is a separate operator path with different contents. \
                    LIVE rows only (soft-deleted rows are excluded; the organization row itself is the \
                    exception, since offboarding runs after the tenant is deleted), each table in a \
                    fixed newest-first order so the same data always produces the same bundle. \
                    `rowCapPerTable` states the cap, and a bundle that hit it says so — \
                    `complete:false` plus `truncatedTables`. Audited as compliance.org_exported.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject exportOrg(@PathVariable UUID orgId) {
        // The tenant is entered EXPLICITLY here, and it should read like the deliberate cross-tenant
        // capability it is (ADR 0010 §5.15 — the machine equivalent of impersonation). The caller is a
        // platform operator: CurrentUserFilter pinned this thread to PLATFORM because an operator has
        // no organization, and the bundle reads tenant-tier tables (membership, ticket,
        // webhook_subscription…) which exist in no schema on that path.
        //
        // It wraps the service call rather than sitting inside it because the service is
        // @Transactional: the schema is chosen at connection borrow, so a pin taken after the
        // transaction opened would be a silent no-op on the previous tenant's search_path —
        // TenantContext.set throws rather than let that happen (ADR 0010 §3.2).
        return new ResourceObject(orgId.toString(), "org-export",
                TenantContext.callAs(orgId, () -> gdprBundle.export(orgId)));
    }

    /**
     * The consequence V34 states out loud, enforced at the door: {@code placed_by_person_id} and
     * {@code requested_by_person_id} are NOT NULL, and a machine principal has no person. So an API key
     * holding {@code platform-admin} cannot place a hold, release one, or execute an erasure — a
     * compliance record with no accountable human is not a record worth keeping. Refused here, where the
     * message can name the credential, rather than as a not-null violation three layers down.
     */
    private static UUID accountablePerson(CurrentUser user) {
        UUID personId = user.accountablePersonId();
        if (personId == null) {
            throw new ForbiddenException("A compliance action records the human accountable for it, and "
                    + "an API key is not one. Call this with a signed-in operator's token.");
        }
        return personId;
    }

    private static UUID required(UUID value, String field) {
        if (value == null) {
            throw new ValidationException(field + " is required.",
                    ApiSource.pointer("/data/attributes/" + field));
        }
        return value;
    }
}
