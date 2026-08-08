package ug.co.smsone.organization.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.identity.PersonDirectory;
import ug.co.smsone.organization.OrgContacts;

/**
 * OWNER memberships joined to identity's e-mail addresses — live, never cached.
 *
 * <h2>The CALLER pins, and it has to be that way round (ADR 0010 §3.2)</h2>
 *
 * <p>{@code org_role} and {@code membership} are TENANT-tier and addressed bare, so this reads nothing
 * at all unless the thread is on {@code orgId}'s axis. Unlike {@link OrgAuthorizationImpl}, which pins
 * for its callers, this port cannot: every caller it has is a billing notifier reacting to an event,
 * and by the time it runs it is already inside that listener's transaction — where declaring an axis
 * throws, because the connection is bound and its schema chosen. The pin therefore has to happen
 * before the listener opens its transaction, which is the caller's own frame and nowhere else.
 *
 * <p>{@code BillingStandingNotifier.send} is the worked example: {@code @Async} +
 * {@code @TransactionalEventListener} instead of {@code @ApplicationModuleListener} precisely so there
 * is a frame left to pin in. A caller that gets this wrong fails loudly — {@code relation "org_role"
 * does not exist}, never a silently empty owner list — because the poison schema holds no table of
 * that name rather than an empty one.
 */
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
