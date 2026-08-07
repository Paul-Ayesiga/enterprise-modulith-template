package ug.co.smsone.organization.internal;

import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.OrgMembers;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The {@link OrgMembers} port: thin mappings over {@link MemberService} — the escalation guard,
 * members-max entitlement gate, MFA-enrollment policy and last-owner lock all live THERE and fire
 * for every surface equally.
 */
@Component
class OrgMembersImpl implements OrgMembers {

    private final MemberService members;

    OrgMembersImpl(MemberService members) {
        this.members = members;
    }

    @Override
    @Transactional(readOnly = true)
    public WindowedResult<MemberView> list(UUID orgId, CursorPageRequest page) {
        Map<UUID, String> roleCodes = members.roleCodes(orgId);
        Window<Membership> window = members.list(orgId, page);
        return WindowedResult.of(window, page, membership -> toView(membership, roleCodes));
    }

    @Override
    public MemberView invite(UUID orgId, String email, String givenName, String familyName, String roleCode) {
        return toView(members.invite(orgId, email, givenName, familyName, roleCode), members.roleCodes(orgId));
    }

    @Override
    public MemberView assignRole(UUID orgId, UUID personId, String roleCode) {
        return toView(members.assignRole(orgId, personId, roleCode), members.roleCodes(orgId));
    }

    @Override
    public void remove(UUID orgId, UUID personId) {
        members.remove(orgId, personId);
    }


    private static MemberView toView(Membership membership, Map<UUID, String> roleCodes) {
        return new MemberView(membership.getPersonId(),
                roleCodes.getOrDefault(membership.getRoleId(), membership.getRoleId().toString()),
                membership.getStatus().name(), membership.getCreatedAt());
    }
}
