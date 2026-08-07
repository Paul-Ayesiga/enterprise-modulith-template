package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.security.OrgMembership;

/**
 * The {@link OrgMembership} port: this module owns {@code membership}, so it answers the question.
 *
 * <p>The interface lives in {@code shared.security} rather than on this module's own {@code OrgMembers}
 * port because {@code access} is the caller and {@code organization} already depends on {@code access}
 * — the direct edge would be a cycle. Inverting it keeps the arrows one-way, which is the property that
 * has to hold when these become separate services.
 */
@Component
class OrgMembershipImpl implements OrgMembership {

    private final MembershipRepository memberships;

    OrgMembershipImpl(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMember(UUID orgId, UUID personId) {
        return orgId != null && personId != null
                && memberships.findByOrgIdAndPersonId(orgId, personId).isPresent();
    }
}
