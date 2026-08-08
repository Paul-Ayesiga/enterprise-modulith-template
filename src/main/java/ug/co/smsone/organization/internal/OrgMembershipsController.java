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
 *
 * <p><b>This is the one person-first read in the codebase, and the reason
 * {@code platform.org_membership_index} exists</b> (ADR 0010 §2.1). Every other membership read
 * already knows its organization — the token's {@code organization} claim resolved it before
 * {@code OrgAuthorization} was called — so it is a single-schema probe. This one does not, and cannot:
 * the caller has not chosen a tenant yet, and a person seated in two organizations resolves to NO
 * organization at all, by design. Asked of the tenant schemas it would be a query per tenant; asked of
 * the index it is one probe on the platform axis the request is already on.
 */
@RestController
@RequestMapping("/api/v1/me")
class OrgMembershipsController {

    private final OrgMembershipIndex membershipIndex;
    private final OrganizationRepository organizations;
    private final MemberService members;

    OrgMembershipsController(OrgMembershipIndex membershipIndex,
            OrganizationRepository organizations, MemberService members) {
        this.membershipIndex = membershipIndex;
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
        List<OrgMembershipIndex.Seat> mine =
                membershipIndex.seatsOf(user.personId(), MembershipStatus.ACTIVE);
        if (mine.isEmpty()) {
            return List.of();
        }
        // Platform-tier and still batched: organization rows are one schema for everybody, so the one
        // batch that survives the split survives it intact. It is also the filter that keeps a
        // soft-deleted organization out of the switcher — @SQLRestriction drops it here, which is why
        // the index deliberately does NOT track the org's own liveness. One authority per fact.
        Map<UUID, Organization> orgs = organizations.findAllById(
                        mine.stream().map(OrgMembershipIndex.Seat::orgId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Organization::getId, org -> org));
        return mine.stream()
                .map(seat -> {
                    Organization org = orgs.get(seat.orgId());
                    if (org == null) {
                        return null; // org soft-deleted under the membership: not a switch target
                    }
                    // 1 + N, knowingly (ADR 0010 §5.4). The role code lives in the ORG's schema, so
                    // this visits each of the caller's own organizations in turn — see
                    // MemberService#roleCodeIn for why the batch that used to do this in one query
                    // cannot survive tenants living in different schemas. N is the caller's org count,
                    // never the tenant count; the index is what guarantees that difference.
                    String roleCode = members.roleCodeIn(seat.orgId(), user.personId());
                    return new ResourceObject(org.getId().toString(), "my-organization",
                            new MyOrgAttributes(org.getAlias(), org.getName(),
                                    org.getStatus().name(), roleCode));
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
