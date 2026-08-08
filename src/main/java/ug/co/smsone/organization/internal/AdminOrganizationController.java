package ug.co.smsone.organization.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The platform's tenant-management surface (docs/plans/TENANT_LIFECYCLE.md is the lifecycle it drives):
 * support READS — every tenant, one tenant, its roster — and admin takes the one destructive step,
 * delete. Class-level {@code /api/v1/admin} mapping keeps the whole surface out of the
 * X-Impersonate docs: an impersonated principal holds no platform tier and every row here refuses.
 */
@RestController
@RequestMapping("/api/v1/admin/orgs")
class AdminOrganizationController {

    private static final String RESOURCE_TYPE = "organization";

    private final OrganizationService organizations;
    private final MemberService members;

    AdminOrganizationController(OrganizationService organizations, MemberService members) {
        this.organizations = organizations;
        this.members = members;
    }

    record AdminOrgAttributes(String alias, String name, String status, Instant createdAt) {
    }

    record AdminMemberAttributes(UUID personId, String roleCode, String status, Instant createdAt) {
    }

    @GetMapping
    @Operation(summary = "List every organization on the platform",
            description = "Newest first; narrow with `status` (ACTIVE or SUSPENDED).")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> list(@RequestParam(name = "status", required = false) String status,
            CursorPageRequest page) {
        return WindowedResult.of(organizations.platformList(parseStatus(status), page), page,
                AdminOrganizationController::toResource);
    }

    @GetMapping("/{orgId}")
    @Operation(summary = "Inspect one organization as the platform")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject get(@PathVariable UUID orgId) {
        return toResource(organizations.require(orgId));
    }

    /**
     * <b>Two pinned spans, and the split is the point</b> (ADR 0010 §2). This route is
     * {@code /api/v1/admin/orgs/…}, so it is NOT matched by {@code CurrentUserFilter}'s org-scoped
     * pattern — deliberately, because the caller is an operator who belongs to no tenant — and it
     * therefore arrives on the PLATFORM axis. That axis is right for the first question and wrong for
     * the second:
     *
     * <ul>
     *   <li><b>Does this tenant exist?</b> {@code platform.organization}, platform-tier. It is a
     *       platform fact and the 404 has to be answerable even for a tenant whose schema is
     *       unreachable, so it stays out here rather than being folded into the span below.</li>
     *   <li><b>Who is in it?</b> {@code membership} and {@code org_role}, TENANT-tier and addressed
     *       bare, so only that tenant's own axis can say which schema they mean. Entering it here,
     *       explicitly, is what {@code CurrentUserFilter} defers to when it declines to match this
     *       path.</li>
     * </ul>
     *
     * <p>The mapping runs INSIDE the tenant span, not after it: {@code roleCodes} is read there and the
     * lambda is what consumes it, so keeping them together means one axis for one question rather than
     * a window whose rows outlive the schema they came from.
     */
    @GetMapping("/{orgId}/members")
    @Operation(summary = "List an organization's members as the platform",
            description = """
                    The support view of a tenant's roster — person ids and role codes, read-only. \
                    Managing members stays a tenant action (or an impersonation session's).""")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> listMembers(@PathVariable UUID orgId, CursorPageRequest page) {
        organizations.require(orgId); // unknown org answers 404, not an empty page — platform axis
        return TenantContext.callAs(orgId, () -> {
            Map<UUID, String> roleCodes = members.roleCodes(orgId);
            return WindowedResult.of(members.list(orgId, page), page,
                    membership -> new ResourceObject(membership.getId().toString(), "membership",
                            new AdminMemberAttributes(membership.getPersonId(),
                                    roleCodes.get(membership.getRoleId()),
                                    membership.getStatus().name(), membership.getCreatedAt())));
        });
    }

    @DeleteMapping("/{orgId}")
    @Operation(summary = "Delete an organization",
            description = """
                    Terminal lifecycle step, `platform-admin`, and only from SUSPENDED (409 \
                    otherwise) — suspension is the reversible step that already cut access. The \
                    row soft-deletes and is restorable until the retention purge; the identity \
                    provider's organization is kept (see the lifecycle doc).""")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID orgId) {
        organizations.delete(orgId);
    }

    private static OrganizationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrganizationStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("status must be ACTIVE or SUSPENDED.",
                    ApiSource.parameter("status"));
        }
    }

    private static ResourceObject toResource(Organization organization) {
        return new ResourceObject(organization.getId().toString(), RESOURCE_TYPE,
                new AdminOrgAttributes(organization.getAlias(), organization.getName(),
                        organization.getStatus().name(), organization.getCreatedAt()));
    }
}
