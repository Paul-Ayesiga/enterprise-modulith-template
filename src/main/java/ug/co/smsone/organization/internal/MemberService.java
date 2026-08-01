package ug.co.smsone.organization.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Adds/removes/re-roles organization members. Invite orchestrates across modules: provision the
 * identity (Keycloak user + temporary credentials, via the identity port), link the Keycloak org
 * membership, then record the local {@code membership} row. Keycloak calls run OUTSIDE any local
 * transaction, so a mid-flight failure leaves at most an INVITED user and no membership (no access) —
 * re-invite is idempotent.
 *
 * <p>Both invite and role assignment pass the {@link PermissionEscalationGuard}: handing someone a
 * role IS granting its permissions, so the caller must hold every permission the role carries —
 * otherwise {@code member:role:assign} alone would be equivalent to OWNER (assign yourself OWNER).
 */
@Service
class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private static final Sort MEMBER_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final UserProvisioning userProvisioning;
    private final KeycloakOrgAdminGateway keycloakOrg;
    private final ApplicationEventPublisher events;
    private final PermissionEscalationGuard escalationGuard;
    private final TransactionTemplate transactionTemplate;
    private final AuditLog auditLog;

    MemberService(MembershipRepository memberships, RoleRepository roles, UserProvisioning userProvisioning,
            KeycloakOrgAdminGateway keycloakOrg, ApplicationEventPublisher events,
            PermissionEscalationGuard escalationGuard, TransactionTemplate transactionTemplate,
            AuditLog auditLog) {
        this.memberships = memberships;
        this.roles = roles;
        this.userProvisioning = userProvisioning;
        this.keycloakOrg = keycloakOrg;
        this.events = events;
        this.escalationGuard = escalationGuard;
        this.transactionTemplate = transactionTemplate;
        this.auditLog = auditLog;
    }

    /** The id → code map the member listing renders with — see {@link RoleRepository#codesByOrgId}. */
    @Transactional(readOnly = true)
    Map<UUID, String> roleCodes(UUID orgId) {
        return roles.codesByOrgId(orgId).stream()
                .collect(Collectors.toMap(RoleRepository.RoleCode::getId, RoleRepository.RoleCode::getCode));
    }

    Membership invite(UUID orgId, String email, String firstName, String lastName, String roleCode) {
        // Resolved twice on purpose. Here for the escalation guard, which must run BEFORE anything is
        // provisioned; again inside saveMembership, because the two Keycloak calls below are network
        // round-trips and the role can be deleted while they are in flight.
        Role role = requireRole(orgId, roleCode);
        escalationGuard.requireCallerHolds(orgId, role.getPermissions());
        ProvisionedUser provisioned = userProvisioning.provision(new ProvisionRequest(email, firstName, lastName));
        keycloakOrg.addMember(orgId, provisioned.subject());
        // Explicit template, not @Transactional: this is a self-invocation, which never reaches the
        // proxy — the same reason remove() opens its transaction this way.
        return transactionTemplate.execute(tx -> saveMembership(orgId, provisioned.subject(), role.getCode()));
    }

    /**
     * Idempotent: a re-invite of an existing member returns the current membership unchanged (role
     * changes go through assignRole, which is last-owner protected). The
     * {@code uq_membership_org_user_live} index backstops a concurrent insert, and losing that race
     * resolves to the winner's row (idempotent success, not a 500).
     *
     * <p>Runs in the caller's transaction, and takes the role by CODE rather than by instance: an
     * instance resolved before the Keycloak calls may be seconds stale, and since soft delete a
     * deleted role still satisfies {@code membership.role_id}, so the insert would succeed against a
     * hidden row. The member would then appear with a null role code and zero permissions, created by
     * a 201, with nothing anywhere explaining why. The shared lock makes the re-read decisive rather
     * than merely narrower — see {@link RoleRepository#lockByOrgIdAndCode}.
     */
    Membership saveMembership(UUID orgId, String subject, String roleCode) {
        return memberships.findByOrgIdAndUserSubject(orgId, subject)
                .orElseGet(() -> {
                    Role role = lockRole(orgId, roleCode);
                    try {
                        Membership created = memberships.save(
                                Membership.create(orgId, subject, role.getId(), role.getCode()));
                        auditLog.record("organization.member_added", orgId, subject, null, "role=" + role.getCode());
                        return created;
                    } catch (DataIntegrityViolationException ex) {
                        return memberships.findByOrgIdAndUserSubject(orgId, subject)
                                .orElseThrow(() -> ex);
                    }
                });
    }

    Window<Membership> list(UUID orgId, CursorPageRequest page) {
        return memberships.findBy(
                (root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                query -> query.limit(page.size()).sortBy(MEMBER_SORT).scroll(page.scrollPosition(MEMBER_SORT)));
    }

    @Transactional
    Membership assignRole(UUID orgId, String subject, String newRoleCode) {
        Membership membership = requireMembership(orgId, subject);
        Role newRole = lockRole(orgId, newRoleCode); // same shared lock as the insert path: no assign-onto-deleted
        if (membership.getRoleId().equals(newRole.getId())) {
            return membership; // no-op, avoids a spurious event + cache flush
        }
        escalationGuard.requireCallerHolds(orgId, newRole.getPermissions());
        guardLastOwnerLoss(orgId, membership); // demoting the last owner would lock the org out
        String previousRole = roles.findById(membership.getRoleId()).map(Role::getCode).orElse(null);
        membership.assignRole(newRole.getId()); // publishes MembershipRoleChanged
        Membership saved = memberships.save(membership);
        auditLog.record("organization.member_role_changed", orgId, subject,
                "role=" + previousRole, "role=" + newRole.getCode());
        return saved;
    }

    void remove(UUID orgId, String subject) {
        // Local delete commits first (with the last-owner guard's row locks released at commit);
        // the Keycloak unlink runs AFTER, outside the transaction — a remote round-trip must never
        // hold a Hikari connection or the pessimistic owner-row locks.
        transactionTemplate.executeWithoutResult(tx -> {
            Membership membership = requireMembership(orgId, subject);
            guardLastOwnerLoss(orgId, membership);
            String previousRole = roles.findById(membership.getRoleId()).map(Role::getCode).orElse(null);
            memberships.delete(membership);
            // A delete does not trigger @DomainEvents, so publish explicitly — evicts the permission cache.
            events.publishEvent(new MemberRemoved(orgId, subject, Instant.now()));
            auditLog.record("organization.member_removed", orgId, subject, "role=" + previousRole, null);
        });
        try {
            keycloakOrg.removeMember(orgId, subject); // unlink from the Keycloak org; user account is kept
        } catch (RuntimeException ex) {
            // Access is already revoked (membership row gone → zero permissions, fail closed). The
            // lingering Keycloak org link only affects the token's organization claim; surface it for
            // ops instead of failing a removal that already happened.
            log.error("Member {} removed from org {} locally, but the Keycloak unlink failed: {}",
                    subject, orgId, ex.toString());
        }
    }

    private void guardLastOwnerLoss(UUID orgId, Membership membership) {
        Role owner = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElse(null);
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
        return roles.findByOrgIdAndCode(orgId, normalize(roleCode))
                .orElseThrow(() -> notFound(roleCode));
    }

    /** {@link #requireRole} for the paths that go on to WRITE the reference — see the repository. */
    private Role lockRole(UUID orgId, String roleCode) {
        return roles.lockByOrgIdAndCode(orgId, normalize(roleCode))
                .orElseThrow(() -> notFound(roleCode));
    }

    private static String normalize(String roleCode) {
        return roleCode == null ? "" : roleCode.trim().toUpperCase();
    }

    private static NotFoundException notFound(String roleCode) {
        return new NotFoundException("Role '" + roleCode + "' not found in this organization.");
    }

    private Membership requireMembership(UUID orgId, String subject) {
        return memberships.findByOrgIdAndUserSubject(orgId, subject)
                .orElseThrow(() -> new NotFoundException("Member not found in this organization."));
    }
}
