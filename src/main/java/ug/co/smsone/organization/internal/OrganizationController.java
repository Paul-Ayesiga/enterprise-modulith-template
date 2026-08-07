package ug.co.smsone.organization.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Organizations. Creation is platform-admin gated (a fresh org has no members yet, so a permission
 * check is impossible); the creating admin names the first owner, who is provisioned and emailed
 * temporary credentials. Read/update are org-scoped — the caller's token must be scoped to {@code orgId}.
 */
@RestController
@RequestMapping("/api/v1/orgs")
class OrganizationController {

    private static final String RESOURCE_TYPE = "organization";

    private final OrganizationService organizations;

    OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    record OrganizationAttributes(String alias, String name, String status) {
    }

    record CreateOrganizationRequest(
            @NotBlank @Size(max = 120) @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,118}[a-z0-9]$",
                    message = "must be a lowercase slug (letters, digits, hyphens)") String alias,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email String ownerEmail,
            // givenName/familyName, not first/last: name ORDER is cultural, so "first" names a
            // position in one rendering rather than the part itself. Both optional — a mononym is an
            // ordinary name — and never concatenated for display.
            String ownerGivenName,
            String ownerFamilyName) {
    }

    record UpdateOrganizationRequest(@NotBlank @Size(max = 200) String name) {
    }

    @PostMapping
    @Operation(summary = "Create an organization and its first owner",
            description = """
                    Creates the tenant and its identity-provider organization, provisions the named \
                    owner (account plus temporary credentials e-mailed to `ownerEmail`), seeds the \
                    org's roles and records the OWNER membership — there is no separate step to add \
                    the first member. An alias already in use, locally or at the provider, is a 409: \
                    an existing organization is never adopted.""")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject create(@Valid @RequestBody CreateOrganizationRequest request) {
        return toResource(organizations.create(request.alias(), request.name(),
                request.ownerEmail(), request.ownerGivenName(), request.ownerFamilyName()).organization());
    }

    @GetMapping("/{orgId}")
    @Operation(summary = "Get an organization")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    ResourceObject get(@PathVariable UUID orgId) {
        return toResource(organizations.require(orgId));
    }

    @PatchMapping("/{orgId}")
    @Operation(summary = "Rename an organization",
            description = "`name` is the only mutable attribute; the alias is fixed at creation.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    ResourceObject update(@PathVariable UUID orgId, @Valid @RequestBody UpdateOrganizationRequest request) {
        return toResource(organizations.rename(orgId, request.name()));
    }

    // Suspension is a platform action (like create): a suspended org's members lose all org access
    // immediately, so it cannot be gated on a permission held inside that same org.

    @PostMapping("/{orgId}/suspend")
    @Operation(summary = "Suspend an organization",
            description = """
                    Every member of a suspended organization resolves to zero permissions, so all of \
                    its org-scoped endpoints refuse them immediately — no role or membership is \
                    changed, and reactivating restores access exactly as it was. Idempotent.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject suspend(@PathVariable UUID orgId) {
        return toResource(organizations.suspend(orgId));
    }

    @PostMapping("/{orgId}/reactivate")
    @Operation(summary = "Reactivate a suspended organization")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject reactivate(@PathVariable UUID orgId) {
        return toResource(organizations.reactivate(orgId));
    }

    // The resource id is organization.id — ours. It used to be the Keycloak organization id, which is
    // how a provider identifier became a public API resource id (V11's header is the post-mortem).
    private static ResourceObject toResource(Organization organization) {
        return new ResourceObject(organization.getId().toString(), RESOURCE_TYPE,
                new OrganizationAttributes(organization.getAlias(), organization.getName(),
                        organization.getStatus().name()));
    }
}
