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

    /** One named group, with its members — the shape of every response that addresses ONE group. */
    record GroupAttributes(String name, String roleCode, List<UUID> members, Instant createdAt) {
    }

    /** A group in a listing: {@code memberCount} where {@link GroupAttributes} has {@code members}. */
    record GroupSummaryAttributes(String name, String roleCode, long memberCount, Instant createdAt) {
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

    /**
     * The listing answers {@code memberCount}, not the member ids — the one decision worth explaining
     * here.
     *
     * <p>It used to serialize every member of every group on the page. That made the response, and the
     * rows behind it, a function of the organization's TOTAL group membership: {@code page[size]=20}
     * over twenty thousand-member groups is twenty thousand ids in one JSON array, and no page size the
     * client picks bounds it. A count is what a list actually renders ("Auditors · 12 members"), it is
     * one aggregate for the whole page, and it cannot grow.
     *
     * <p>The ids are one hop away and bounded there: {@code GET /groups/{id}} returns them for the group
     * the caller names. That endpoint is deliberately NOT paginated — a group's membership is bounded by
     * the organization's headcount, which the plan's member entitlement already caps — so this is a
     * summary/detail split, not a truncation the client has to detect. If member sets ever outgrow one
     * response, the sub-resource to paginate is {@code /groups/{id}/members}, and the listing's shape
     * would not change.
     */
    @GetMapping
    @Operation(summary = "List the organization's groups",
            description = "Each row carries `memberCount`; the member ids come from the single-group "
                    + "endpoint, so a page never grows with the organization's total group membership.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(groups.list(orgId, page), page, OrgGroupController::toSummaryResource);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one group with its members")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:read')")
    ResourceObject get(@PathVariable UUID orgId, @PathVariable UUID id) {
        return toResource(groups.get(orgId, id));
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

    private static ResourceObject toResource(OrgGroupService.GroupDetail group) {
        return new ResourceObject(group.id().toString(), RESOURCE_TYPE,
                new GroupAttributes(group.name(), group.roleCode(), group.members(), group.createdAt()));
    }

    /** Named apart from {@link #toResource} rather than overloading it: two shapes, two names. */
    private static ResourceObject toSummaryResource(OrgGroupService.GroupSummary group) {
        return new ResourceObject(group.id().toString(), RESOURCE_TYPE,
                new GroupSummaryAttributes(group.name(), group.roleCode(), group.memberCount(),
                        group.createdAt()));
    }
}
