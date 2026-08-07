package ug.co.smsone.organization.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.identity.PersonDirectory;
import ug.co.smsone.organization.OrgContacts;

/** OWNER memberships joined to identity's e-mail addresses — live, never cached. */
@Component
class OrgContactsImpl implements OrgContacts {

    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final PersonDirectory people;

    OrgContactsImpl(MembershipRepository memberships, RoleRepository roles, PersonDirectory people) {
        this.memberships = memberships;
        this.roles = roles;
        this.people = people;
    }

    @Override
    public List<String> ownerEmails(UUID orgId) {
        List<UUID> personIds = roles.findByOrgIdAndCode(orgId, "OWNER")
                .map(owner -> memberships.findByOrgIdAndRoleId(orgId, owner.getId()).stream()
                        .map(Membership::getPersonId)
                        .toList())
                .orElse(List.of());
        // Owners with no e-mail on file are simply absent from the map — the port's documented shape,
        // and the right one here: there is no address to dun them at, so there is nothing to return.
        return personIds.isEmpty() ? List.of()
                : List.copyOf(people.emailsByPersonIds(personIds).values());
    }
}
