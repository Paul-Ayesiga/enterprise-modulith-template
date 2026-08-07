package ug.co.smsone.organization.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
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
 * Organization members. Invite provisions the person (account plus temporary credentials) and
 * links the membership in one call. Remove and role-reassign are last-owner protected. All endpoints
 * are org-scoped via {@code hasPermission(#orgId, ...)}.
 *
 * <p>A member is addressed by {@code personId} — this platform's own identifier for a human, stable
 * across every identity provider they ever sign in with. It used to be their Keycloak subject, which
 * is a value one provider minted and no second one could ever produce.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/members")
class MemberController {

    private static final String RESOURCE_TYPE = "member";

    private final MemberService members;

    MemberController(MemberService members) {
        this.members = members;
    }

    record MemberAttributes(UUID personId, String roleId, String roleCode, String status) {
    }

    record InviteMemberRequest(@NotBlank @Email String email, String givenName, String familyName,
            @NotBlank String roleCode) {
    }

    record AssignRoleRequest(@NotBlank String roleCode) {
    }

    @GetMapping
    @Operation(summary = "List organization members")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:read')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        Map<UUID, String> roleCodes = roleCodesFor(orgId);
        Window<Membership> window = members.list(orgId, page);
        return WindowedResult.of(window, page, membership -> toResource(membership, roleCodes));
    }

    @PostMapping
    @Operation(summary = "Invite a member to the organization",
            description = """
                    One call does three things: it provisions the person (creating their account and \
                    e-mailing temporary credentials if they are new), attaches them to the \
                    organization at the identity provider, and records the membership. Re-inviting an \
                    existing member is idempotent and returns the current membership unchanged — it \
                    does not re-role them. Handing over a role is granting its permissions, so the \
                    caller must already hold every permission `roleCode` carries. `givenName` and \
                    `familyName` are both optional: a mononym is an ordinary name.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:invite')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject invite(@PathVariable UUID orgId, @Valid @RequestBody InviteMemberRequest request) {
        Membership membership = members.invite(orgId, request.email(), request.givenName(),
                request.familyName(), request.roleCode());
        return toResource(membership, roleCodesFor(orgId));
    }

    @PutMapping("/{personId}/role")
    @Operation(summary = "Reassign a member's role",
            description = """
                    Last-owner protected: demoting the only remaining OWNER is a 409, because it would \
                    lock the organization out of its own administration. The caller must already hold \
                    every permission the new role carries, so this endpoint alone cannot escalate. \
                    Assigning the role the member already has is a no-op.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:role:assign')")
    ResourceObject assignRole(@PathVariable UUID orgId, @PathVariable UUID personId,
            @Valid @RequestBody AssignRoleRequest request) {
        Membership membership = members.assignRole(orgId, personId, request.roleCode());
        return toResource(membership, roleCodesFor(orgId));
    }

    @DeleteMapping("/{personId}")
    @Operation(summary = "Remove a member from the organization",
            description = """
                    Unlinks the membership and the identity provider's organization link only — the \
                    person's account is never deleted, so they keep their identity and any membership \
                    of other organizations. Removing the last OWNER is a 409.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'member:remove')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID orgId, @PathVariable UUID personId) {
        members.remove(orgId, personId);
    }

    private Map<UUID, String> roleCodesFor(UUID orgId) {
        return members.roleCodes(orgId);
    }

    private static ResourceObject toResource(Membership membership, Map<UUID, String> roleCodes) {
        return new ResourceObject(membership.getPersonId().toString(), RESOURCE_TYPE,
                new MemberAttributes(membership.getPersonId(), membership.getRoleId().toString(),
                        roleCodes.get(membership.getRoleId()), membership.getStatus().name()));
    }
}
