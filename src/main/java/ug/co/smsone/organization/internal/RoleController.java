package ug.co.smsone.organization.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Org-scoped custom roles (bundles of permissions). {@code OWNER} is the one system role — visible but
 * immutable, so update/delete on it return 403; every other role in an org was created here. All
 * endpoints are org-scoped via {@code hasPermission}.
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
    @Operation(summary = "List the organization's roles")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'role:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(roles.list(orgId, page), page, RoleController::toResource);
    }

    @GetMapping("/{roleId}")
    @Operation(summary = "Get one organization role")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'role:read')")
    ResourceObject get(@PathVariable UUID orgId, @PathVariable UUID roleId) {
        return toResource(roles.require(orgId, roleId));
    }

    @PostMapping
    @Operation(summary = "Create a custom organization role",
            description = """
                    `permissions` holds codes from `GET /api/v1/permissions`; an unknown one is a 422. \
                    The caller must already hold every permission the new role bundles, so a role \
                    cannot be used to mint authority its author lacks. `code` is upper-cased, must be \
                    unique in the organization, and may be neither `OWNER` nor anything starting \
                    `PLATFORM`.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'role:create')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject create(@PathVariable UUID orgId, @Valid @RequestBody CreateRoleRequest request) {
        return toResource(roles.create(orgId, request.code(), request.name(),
                request.description(), request.permissions()));
    }

    @PutMapping("/{roleId}")
    @Operation(summary = "Replace a role's name and permissions",
            description = """
                    `permissions` is replaced wholesale, not merged — omitting a code revokes it from \
                    every member holding the role. The caller must already hold every permission in \
                    the new set. The built-in `OWNER` role is immutable: updating it is a 403. \
                    `code` cannot be changed.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'role:update')")
    ResourceObject update(@PathVariable UUID orgId, @PathVariable UUID roleId,
            @Valid @RequestBody UpdateRoleRequest request) {
        return toResource(roles.update(orgId, roleId, request.name(),
                request.description(), request.permissions()));
    }

    @DeleteMapping("/{roleId}")
    @Operation(summary = "Delete a custom organization role",
            description = """
                    A role still assigned to any member is a 409 — reassign them first. The built-in \
                    `OWNER` role cannot be deleted at all (403).""")
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
