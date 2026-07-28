package ug.co.smsone.organization.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Org-scoped custom roles (bundles of permissions). System roles (OWNER/ADMIN/MEMBER) are visible but
 * immutable — update/delete on them return 403. All endpoints are org-scoped via {@code hasPermission}.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/roles")
class RoleController {

    private static final String RESOURCE_TYPE = "role";

    private final RoleService roles;

    RoleController(RoleService roles) {
        this.roles = roles;
    }

    record RoleAttributes(String code, String name, String description, boolean systemRole,
            Set<String> permissions) {
    }

    record CreateRoleRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,62}$",
                    message = "must start with a letter and contain only letters, digits, underscores") String code,
            @NotBlank @Size(max = 120) String name,
            String description,
            @NotEmpty Set<String> permissions) {
    }

    record UpdateRoleRequest(@NotBlank @Size(max = 120) String name, String description,
            @NotEmpty Set<String> permissions) {
    }

    @GetMapping
    @PreAuthorize("hasPermission(#orgId, 'organization', 'role:read')")
    List<ResourceObject> list(@PathVariable UUID orgId) {
        return roles.list(orgId).stream().map(RoleController::toResource).toList();
    }

    @GetMapping("/{roleId}")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'role:read')")
    ResourceObject get(@PathVariable UUID orgId, @PathVariable UUID roleId) {
        return toResource(roles.require(orgId, roleId));
    }

    @PostMapping
    @PreAuthorize("hasPermission(#orgId, 'organization', 'role:create')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject create(@PathVariable UUID orgId, @Valid @RequestBody CreateRoleRequest request) {
        return toResource(roles.create(orgId, request.code(), request.name(),
                request.description(), request.permissions()));
    }

    @PutMapping("/{roleId}")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'role:update')")
    ResourceObject update(@PathVariable UUID orgId, @PathVariable UUID roleId,
            @Valid @RequestBody UpdateRoleRequest request) {
        return toResource(roles.update(orgId, roleId, request.name(),
                request.description(), request.permissions()));
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'role:delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID orgId, @PathVariable UUID roleId) {
        roles.delete(orgId, roleId);
    }

    private static ResourceObject toResource(Role role) {
        Set<String> permissions = role.getPermissions().stream()
                .map(Permission::code)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        return new ResourceObject(role.getId().toString(), RESOURCE_TYPE,
                new RoleAttributes(role.getCode(), role.getName(), role.getDescription(),
                        role.isSystemRole(), permissions));
    }
}
