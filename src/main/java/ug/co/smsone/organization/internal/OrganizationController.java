package ug.co.smsone.organization.internal;

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
            String ownerFirstName,
            String ownerLastName) {
    }

    record UpdateOrganizationRequest(@NotBlank @Size(max = 200) String name) {
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject create(@Valid @RequestBody CreateOrganizationRequest request) {
        return toResource(organizations.create(request.alias(), request.name(),
                request.ownerEmail(), request.ownerFirstName(), request.ownerLastName()));
    }

    @GetMapping("/{orgId}")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    ResourceObject get(@PathVariable UUID orgId) {
        return toResource(organizations.require(orgId));
    }

    @PatchMapping("/{orgId}")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    ResourceObject update(@PathVariable UUID orgId, @Valid @RequestBody UpdateOrganizationRequest request) {
        return toResource(organizations.rename(orgId, request.name()));
    }

    private static ResourceObject toResource(Organization organization) {
        return new ResourceObject(organization.getKcOrgId().toString(), RESOURCE_TYPE,
                new OrganizationAttributes(organization.getAlias(), organization.getName(),
                        organization.getStatus().name()));
    }
}
