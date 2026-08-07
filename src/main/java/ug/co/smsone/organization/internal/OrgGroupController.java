package ug.co.smsone.organization.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * An organization's user groups — named funnels that confer one role to their members on top of
 * each member's own. Gated on {@code member:role:assign}: adding someone to a group grants that
 * group's permissions, so it is the same authority as assigning a role directly, and the same
 * escalation guard applies.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/groups")
class OrgGroupController {

    static final String RESOURCE_TYPE = "group";

    private final OrgGroupService groups;

    OrgGroupController(OrgGroupService groups) {
        this.groups = groups;
    }

    record GroupAttributes(String name, String roleCode, List<UUID> members, Instant createdAt) {
    }

    record CreateRequest(String name, String roleCode) {
    }

    record RoleRequest(String roleCode) {
    }

    record MemberRequest(UUID personId) {
    }

    @PostMapping
    @Operation(summary = "Create a user group",
            description = "Confers `roleCode` to every member added. Refused if you don't hold that "
                    + "role's permissions (a group can't grant more than its creator holds).")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:role:assign')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject create(@PathVariable UUID orgId, @RequestBody CreateRequest request) {
        return toResource(groups.create(orgId, request.name(), request.roleCode()));
    }

    @GetMapping
    @Operation(summary = "List the organization's groups")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(groups.list(orgId, page), page, this::toResource);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one group with its members")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:read')")
    ResourceObject get(@PathVariable UUID orgId, @PathVariable UUID id) {
        return toResource(groups.require(orgId, id));
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "Reassign the group's conferred role")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:role:assign')")
    ResourceObject reassignRole(@PathVariable UUID orgId, @PathVariable UUID id, @RequestBody RoleRequest request) {
        return toResource(groups.reassignRole(orgId, id, request.roleCode()));
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "Add a member to the group",
            description = "The person must already be an organization member.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:role:assign')")
    ResourceObject addMember(@PathVariable UUID orgId, @PathVariable UUID id, @RequestBody MemberRequest request) {
        return toResource(groups.addMember(orgId, id, request.personId()));
    }

    @DeleteMapping("/{id}/members/{personId}")
    @Operation(summary = "Remove a member from the group")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:role:assign')")
    ResourceObject removeMember(@PathVariable UUID orgId, @PathVariable UUID id, @PathVariable UUID personId) {
        return toResource(groups.removeMember(orgId, id, personId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a group")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:role:assign')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        groups.delete(orgId, id);
    }

    private ResourceObject toResource(OrgGroup group) {
        return new ResourceObject(group.getId().toString(), RESOURCE_TYPE,
                new GroupAttributes(group.getName(), groups.roleCode(group.getRoleId()),
                        List.copyOf(group.getMembers()), group.getCreatedAt()));
    }
}
