package ug.co.smsone.organization.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.identity.UserDirectory;
import ug.co.smsone.organization.OrgContacts;

/** OWNER memberships joined to the identity projection's emails — live, never cached. */
@Component
class OrgContactsImpl implements OrgContacts {

    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final UserDirectory users;

    OrgContactsImpl(MembershipRepository memberships, RoleRepository roles, UserDirectory users) {
        this.memberships = memberships;
        this.roles = roles;
        this.users = users;
    }

    @Override
    public List<String> ownerEmails(UUID orgId) {
        List<String> subjects = roles.findByOrgIdAndCode(orgId, "OWNER")
                .map(owner -> memberships.findByOrgIdAndRoleId(orgId, owner.getId()).stream()
                        .map(Membership::getUserSubject)
                        .toList())
                .orElse(List.of());
        return subjects.isEmpty() ? List.of()
                : List.copyOf(users.emailsBySubjects(subjects).values());
    }
}
