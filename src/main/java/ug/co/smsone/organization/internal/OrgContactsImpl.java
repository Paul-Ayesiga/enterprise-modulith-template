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
    private final UserDirectory users;

    OrgContactsImpl(MembershipRepository memberships, UserDirectory users) {
        this.memberships = memberships;
        this.users = users;
    }

    @Override
    public List<String> ownerEmails(UUID orgId) {
        List<String> subjects = memberships.findByOrgIdAndRoleCode(orgId, "OWNER").stream()
                .map(Membership::getUserSubject)
                .toList();
        return subjects.isEmpty() ? List.of()
                : List.copyOf(users.emailsBySubjects(subjects).values());
    }
}
