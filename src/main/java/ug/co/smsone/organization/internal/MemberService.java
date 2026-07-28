package ug.co.smsone.organization.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Adds/removes/re-roles organization members. Invite orchestrates across modules: provision the
 * identity (Keycloak user + temporary credentials, via the identity port), link the Keycloak org
 * membership, then record the local {@code membership} row. Keycloak calls run OUTSIDE the local
 * transaction, so a mid-flight failure leaves at most an INVITED user and no membership (no access) —
 * re-invite is idempotent.
 */
@Service
class MemberService {

    private static final Sort MEMBER_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final UserProvisioning userProvisioning;
    private final KeycloakOrgAdminGateway keycloakOrg;
    private final ApplicationEventPublisher events;

    MemberService(MembershipRepository memberships, RoleRepository roles, UserProvisioning userProvisioning,
            KeycloakOrgAdminGateway keycloakOrg, ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.roles = roles;
        this.userProvisioning = userProvisioning;
        this.keycloakOrg = keycloakOrg;
        this.events = events;
    }

    Membership invite(UUID orgId, String email, String firstName, String lastName, String roleCode) {
        Role role = requireRole(orgId, roleCode);
        ProvisionedUser provisioned = userProvisioning.provision(new ProvisionRequest(email, firstName, lastName));
        keycloakOrg.addMember(orgId, provisioned.subject());
        return saveMembership(orgId, provisioned.subject(), role);
    }

    Membership saveMembership(UUID orgId, String subject, Role role) {
        // Idempotent: a re-invite of an existing member returns the current membership unchanged
        // (role changes go through assignRole, which is last-owner protected). No @Transactional needed
        // — this is a single save; the uq_membership_org_user constraint backstops a concurrent insert.
        return memberships.findByOrgIdAndUserSubject(orgId, subject)
                .orElseGet(() -> memberships.save(Membership.create(orgId, subject, role.getId(), role.getCode())));
    }

    Window<Membership> list(UUID orgId, CursorPageRequest page) {
        return memberships.findBy(
                (root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                query -> query.limit(page.size()).sortBy(MEMBER_SORT).scroll(page.scrollPosition()));
    }

    @Transactional
    Membership assignRole(UUID orgId, String subject, String newRoleCode) {
        Membership membership = requireMembership(orgId, subject);
        Role newRole = requireRole(orgId, newRoleCode);
        if (membership.getRoleId().equals(newRole.getId())) {
            return membership; // no-op, avoids a spurious event + cache flush
        }
        guardLastOwnerLoss(orgId, membership); // demoting the last owner would lock the org out
        membership.assignRole(newRole.getId()); // publishes MembershipRoleChanged
        return memberships.save(membership);
    }

    @Transactional
    void remove(UUID orgId, String subject) {
        Membership membership = requireMembership(orgId, subject);
        guardLastOwnerLoss(orgId, membership);
        memberships.delete(membership);
        // A delete does not trigger @DomainEvents, so publish explicitly — evicts the permission cache.
        events.publishEvent(new MemberRemoved(orgId, subject, Instant.now()));
        keycloakOrg.removeMember(orgId, subject); // unlink from the Keycloak org; user account is kept
    }

    private void guardLastOwnerLoss(UUID orgId, Membership membership) {
        Role owner = roles.findByOrgIdAndCode(orgId, "OWNER").orElse(null);
        if (owner == null || !membership.getRoleId().equals(owner.getId())) {
            return; // not an owner — removing/demoting is always fine
        }
        // Row-lock the owner set so concurrent removals/demotions serialize (no zero-owner race).
        long owners = memberships.lockByOrgIdAndRoleIdAndStatus(orgId, owner.getId(), MembershipStatus.ACTIVE).size();
        if (owners <= 1) {
            throw new ConflictException("Cannot remove or demote the last owner of the organization.");
        }
    }

    private Role requireRole(UUID orgId, String roleCode) {
        return roles.findByOrgIdAndCode(orgId, roleCode == null ? "" : roleCode.trim().toUpperCase())
                .orElseThrow(() -> new NotFoundException("Role '" + roleCode + "' not found in this organization."));
    }

    private Membership requireMembership(UUID orgId, String subject) {
        return memberships.findByOrgIdAndUserSubject(orgId, subject)
                .orElseThrow(() -> new NotFoundException("Member not found in this organization."));
    }
}
