package ug.co.smsone.compliance.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
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
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The platform's compliance controls: place and release legal holds (which BLOCK the purge and any
 * erasure of the held subject/org), and execute an erasure on a subject's behalf. Placing holds is
 * platform-admin; listing is platform-support.
 */
@RestController
@RequestMapping("/api/v1/admin/compliance")
class AdminComplianceController {

    // -- org export (tenant offboarding) --
    private final OrgExportService orgExport;

    private final ComplianceService compliance;

    AdminComplianceController(ComplianceService compliance, OrgExportService orgExport) {
        this.compliance = compliance;
        this.orgExport = orgExport;
    }

    record SubjectHoldRequest(String subject, String reason) {
    }

    record OrgHoldRequest(String orgId, String reason) {
    }

    record ErasureRequestBody(String subject) {
    }

    @GetMapping("/legal-holds")
    @Operation(summary = "List active legal holds")
    @PreAuthorize("hasRole('platform-support')")
    List<ResourceObject> holds() {
        return compliance.activeHolds().stream()
                .map(h -> new ResourceObject(h.getId().toString(), "legal-hold",
                        Map.of("scope", h.getScope(),
                                "subject", h.getSubject() == null ? "" : h.getSubject(),
                                "orgId", h.getOrgId() == null ? "" : h.getOrgId().toString(),
                                "reason", h.getReason(), "placedAt", h.getPlacedAt())))
                .toList();
    }

    @PostMapping("/legal-holds/subject")
    @Operation(summary = "Place a legal hold on a subject",
            description = "While active, the subject's data cannot be purged or erased.")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject holdSubject(@RequestBody SubjectHoldRequest request, CurrentUser user) {
        var hold = compliance.placeSubjectHold(request.subject(), request.reason(), user.accountableSubject());
        return new ResourceObject(hold.getId().toString(), "legal-hold",
                Map.of("scope", "SUBJECT", "subject", request.subject()));
    }

    @PostMapping("/legal-holds/org")
    @Operation(summary = "Place a legal hold on an organization")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject holdOrg(@RequestBody OrgHoldRequest request, CurrentUser user) {
        var hold = compliance.placeOrgHold(UUID.fromString(request.orgId()), request.reason(),
                user.accountableSubject());
        return new ResourceObject(hold.getId().toString(), "legal-hold",
                Map.of("scope", "ORG", "orgId", request.orgId()));
    }

    @DeleteMapping("/legal-holds/{id}")
    @Operation(summary = "Release a legal hold")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID id, CurrentUser user) {
        compliance.releaseHold(id, user.accountableSubject());
    }

    @PostMapping("/erasure")
    @Operation(summary = "Execute an erasure on a subject's behalf",
            description = "REFUSED while a legal hold is in force for the subject.")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject erase(@RequestBody ErasureRequestBody request, CurrentUser user) {
        ErasureRequest erasure = compliance.requestErasure(request.subject(), user.accountableSubject());
        return new ResourceObject(erasure.getId().toString(), "erasure-request",
                Map.of("status", erasure.getStatus(),
                        "detail", erasure.getDetail() == null ? "" : erasure.getDetail()));
    }

    @org.springframework.web.bind.annotation.GetMapping("/orgs/{orgId}/export")
    @io.swagger.v3.oas.annotations.Operation(summary = "Export one organization's records (offboarding bundle)",
            description = """
                    A JSON bundle of the organization's rows across every org-owned table — membership, \
                    roles, subscription, integrations, webhooks, payments, usage, tickets, audit (capped \
                    per table; the cap is stated in the payload). Secret-bearing columns never leave the \
                    database. Audited as compliance.org_exported.""")
    @PreAuthorize("hasRole('platform-admin')")
    ug.co.smsone.shared.web.ResourceObject exportOrg(@org.springframework.web.bind.annotation.PathVariable java.util.UUID orgId) {
        return new ug.co.smsone.shared.web.ResourceObject(orgId.toString(), "org-export", orgExport.export(orgId));
    }
}
