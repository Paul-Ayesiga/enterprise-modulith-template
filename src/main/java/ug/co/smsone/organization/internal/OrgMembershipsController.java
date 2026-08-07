package ug.co.smsone.organization.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Every organization the CALLER belongs to — the list a dual member's client renders to offer an
 * organization switch. The switch itself is a TOKEN act (re-request scoped to the chosen org via
 * the identity provider's {@code organization} claim); the server never trusts a client-asserted
 * active org, which is exactly why this endpoint only lists.
 */
@RestController
@RequestMapping("/api/v1/me")
class OrgMembershipsController {

    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final MemberService members;

    OrgMembershipsController(MembershipRepository memberships,
            OrganizationRepository organizations, MemberService members) {
        this.memberships = memberships;
        this.organizations = organizations;
        this.members = members;
    }

    record MyOrgAttributes(String alias, String name, String status, String roleCode) {
    }

    @GetMapping("/organizations")
    @Operation(summary = "List the organizations you belong to",
            description = """
                    One row per ACTIVE membership, with your role there. Switching = requesting a \
                    token scoped to the chosen organization (the identity provider's `organization` \
                    claim); the current token's active org keeps governing until you do.""")
    List<ResourceObject> myOrganizations(CurrentUser user) {
        // A machine key has no memberships to list — it is not a person and belongs to exactly one
        // tenant already. An unprovisioned human has none either; both answer an empty list.
        if (user.personId() == null) {
            return List.of();
        }
        List<Membership> mine = memberships.findByPersonIdAndStatus(user.personId(), MembershipStatus.ACTIVE);
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<UUID, Organization> orgs = organizations.findAllById(
                        mine.stream().map(Membership::getOrgId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Organization::getId, org -> org));
        return mine.stream()
                .map(membership -> {
                    Organization org = orgs.get(membership.getOrgId());
                    if (org == null) {
                        return null; // org soft-deleted under the membership: not a switch target
                    }
                    String roleCode = members.roleCodes(membership.getOrgId())
                            .get(membership.getRoleId());
                    return new ResourceObject(org.getId().toString(), "my-organization",
                            new MyOrgAttributes(org.getAlias(), org.getName(),
                                    org.getStatus().name(), roleCode));
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
