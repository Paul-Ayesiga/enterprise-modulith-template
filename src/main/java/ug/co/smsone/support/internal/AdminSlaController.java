package ug.co.smsone.support.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Per-org SLA overrides: the platform sets an org's SLA targets tighter (or looser) than the seeded
 * per-priority defaults — an enterprise contract, typically. Consulted at ticket open; clearing one
 * falls the org back to the seeded policy for that priority. Reads are platform-support, writes
 * platform-admin (they change a contractual commitment), and every change is audited.
 *
 * <p><b>Every route here is a cross-tenant reach, and every one of them says so</b> (ADR 0010 §5.15).
 * A {@code platform-admin} has no organization of their own, so these requests arrive on the PLATFORM
 * axis — and {@code org_sla_override} is tenant-tier, which that axis cannot see at all. The contract
 * belongs to the org that signed it and travels with that org on extraction, which is why the row is
 * the tenant's and not the platform's.
 *
 * <p>The pin wraps the call rather than sitting inside {@link SupportService}, whose methods are
 * {@code @Transactional}: the schema is chosen when the connection is borrowed and the transaction has
 * already borrowed one, which is why {@code TenantContext.set} throws in there. Same shape, same
 * reason, as {@code AdminRetentionController}.
 */
@RestController
@RequestMapping("/api/v1/admin/orgs/{orgId}/sla")
class AdminSlaController {

    private final SupportService support;

    AdminSlaController(SupportService support) {
        this.support = support;
    }

    record SlaRequest(Integer firstResponseMinutes, Integer resolutionMinutes) {
    }

    record SlaAttributes(String priority, int firstResponseMinutes, int resolutionMinutes) {
    }

    @GetMapping
    @Operation(summary = "List an org's SLA overrides",
            description = "Priorities not listed fall back to the seeded per-priority default.")
    @PreAuthorize("hasRole('platform-support')")
    List<ResourceObject> list(@PathVariable UUID orgId) {
        return TenantContext.callAs(orgId, () -> support.listSlaOverrides(orgId)).stream()
                .map(AdminSlaController::toResource).toList();
    }

    @PutMapping("/{priority}")
    @Operation(summary = "Set an org's SLA target for a priority",
            description = "Upserts the org's first-response and resolution minutes for `priority` "
                    + "(P1–P4), overriding the seeded default. Takes effect on tickets opened after.")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject set(@PathVariable UUID orgId, @PathVariable String priority,
            @RequestBody SlaRequest request) {
        int first = request.firstResponseMinutes() == null ? 0 : request.firstResponseMinutes();
        int resolution = request.resolutionMinutes() == null ? 0 : request.resolutionMinutes();
        return toResource(
                TenantContext.callAs(orgId, () -> support.setSlaOverride(orgId, priority, first, resolution)));
    }

    @DeleteMapping("/{priority}")
    @Operation(summary = "Clear an org's SLA override (fall back to the seeded default)")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@PathVariable UUID orgId, @PathVariable String priority) {
        TenantContext.runAs(orgId, () -> support.clearSlaOverride(orgId, priority));
    }

    private static ResourceObject toResource(OrgSlaOverride override) {
        return new ResourceObject(override.getOrgId() + ":" + override.getPriority(), "sla-override",
                new SlaAttributes(override.getPriority(), override.getFirstResponseMinutes(),
                        override.getResolutionMinutes()));
    }
}
