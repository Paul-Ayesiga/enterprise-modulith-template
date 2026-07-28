package ug.co.smsone.organization.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Window;
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
 * Organization members. Invite provisions the identity (Keycloak user + temporary credentials) and
 * links the membership in one call. Remove and role-reassign are last-owner protected. All endpoints
 * are org-scoped via {@code hasPermission(#orgId, ...)}. {@code subject} is the member's Keycloak user id.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/members")
class MemberController {

    private static final String RESOURCE_TYPE = "member";

    private final MemberService members;
    private final RoleRepository roles;

    MemberController(MemberService members, RoleRepository roles) {
        this.members = members;
        this.roles = roles;
    }

    record MemberAttributes(String subject, String roleId, String roleCode, String status) {
    }

    record InviteMemberRequest(@NotBlank @Email String email, String firstName, String lastName,
            @NotBlank String roleCode) {
    }

    record AssignRoleRequest(@NotBlank String roleCode) {
    }

    @GetMapping
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        Map<UUID, String> roleCodes = roleCodesFor(orgId);
        Window<Membership> window = members.list(orgId, page);
        return WindowedResult.of(window, page, membership -> toResource(membership, roleCodes));
    }

    @PostMapping
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:invite')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject invite(@PathVariable UUID orgId, @Valid @RequestBody InviteMemberRequest request) {
        Membership membership = members.invite(orgId, request.email(), request.firstName(),
                request.lastName(), request.roleCode());
        return toResource(membership, roleCodesFor(orgId));
    }

    @PutMapping("/{subject}/role")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:role:assign')")
    ResourceObject assignRole(@PathVariable UUID orgId, @PathVariable String subject,
            @Valid @RequestBody AssignRoleRequest request) {
        Membership membership = members.assignRole(orgId, subject, request.roleCode());
        return toResource(membership, roleCodesFor(orgId));
    }

    @DeleteMapping("/{subject}")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:remove')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID orgId, @PathVariable String subject) {
        members.remove(orgId, subject);
    }

    /** One query per request maps opaque role ids to their human-readable codes for the response. */
    private Map<UUID, String> roleCodesFor(UUID orgId) {
        return roles.findByOrgId(orgId).stream()
                .collect(Collectors.toMap(Role::getId, Role::getCode));
    }

    private static ResourceObject toResource(Membership membership, Map<UUID, String> roleCodes) {
        return new ResourceObject(membership.getUserSubject(), RESOURCE_TYPE,
                new MemberAttributes(membership.getUserSubject(), membership.getRoleId().toString(),
                        roleCodes.get(membership.getRoleId()), membership.getStatus().name()));
    }
}
