package ug.co.smsone.organization.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.ProviderOrgMembership;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Adds/removes/re-roles organization members. Invite orchestrates across modules: provision the
 * person (identity port — person row, Keycloak account, temporary credentials), attach them to the
 * provider organization so their next token carries its {@code organization} claim, then record the
 * local {@code membership} row. The remote calls run OUTSIDE any local transaction, so a mid-flight
 * failure leaves at most an INVITED person and no membership (no access) — re-invite is idempotent.
 *
 * <p>A member is named by {@code person.id} throughout. The Keycloak subject behind that person never
 * reaches this module: {@link ProviderOrgMembership} takes a person and the provider organization id,
 * and identity does the translation it alone can do.
 *
 * <p>Both invite and role assignment pass the {@link PermissionEscalationGuard}: handing someone a
 * role IS granting its permissions, so the caller must hold every permission the role carries —
 * otherwise {@code member:role:assign} alone would be equivalent to OWNER (assign yourself OWNER).
 *
 * <p><b>Every write that creates or ends a seat also writes {@link OrgMembershipIndex}, inside the
 * same transaction</b> (ADR 0010 §2.1). {@code membership} is tenant-tier and the index is the one
 * platform-side copy of "which orgs is this person in" — the only question no tenant schema can
 * answer. Role changes do not touch it: it holds routing keys and no role, on purpose, because the
 * index routes and the tenant schema authorizes.
 *
 * <p><b>Which axis this class runs on: the caller's, and every caller already declares it.</b> All of
 * {@code membership}, {@code org_role} and {@code role_permission} are tenant-tier and addressed bare,
 * so nothing here works without one — and nothing here pins one either, because each entry point is
 * reached for the org that is already on the thread: an org-scoped request pinned by
 * {@code CurrentUserFilter}, an MCP tool call pinned by {@code McpToolDispatcher}, a bulk import pinned
 * by {@code ExchangeWorker}, the operator roster pinned by {@code AdminOrganizationController}. The one
 * exception is {@link #roleCodeIn}, which is asked about an org the caller is NOT in, and pins for
 * itself — see its javadoc. That cross-tier write in {@link #saveMembership} needs one span and not
 * two; {@link OrgMembershipIndex}'s {@code TABLE} note is where that is argued.
 */
@Service
class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private static final Sort MEMBER_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final MembershipRepository memberships;
    private final OrgMembershipIndex membershipIndex;
    private final RoleRepository roles;
    private final PersonProvisioning personProvisioning;
    private final ProviderOrgMembership providerOrgMembership;
    private final OrgResolver orgResolver;
    private final ApplicationEventPublisher events;
    private final PermissionEscalationGuard escalationGuard;
    private final TransactionTemplate transactionTemplate;
    private final AuditLog auditLog;
    private final ug.co.smsone.subscription.Entitlements entitlements;
    private final org.springframework.beans.factory.ObjectProvider<ug.co.smsone.access.OrgSecurityPolicies> securityPolicies;

    MemberService(MembershipRepository memberships, OrgMembershipIndex membershipIndex,
            RoleRepository roles, PersonProvisioning personProvisioning,
            ProviderOrgMembership providerOrgMembership, OrgResolver orgResolver,
            ApplicationEventPublisher events,
            PermissionEscalationGuard escalationGuard, TransactionTemplate transactionTemplate,
            AuditLog auditLog, ug.co.smsone.subscription.Entitlements entitlements,
            org.springframework.beans.factory.ObjectProvider<ug.co.smsone.access.OrgSecurityPolicies> securityPolicies) {
        this.memberships = memberships;
        this.membershipIndex = membershipIndex;
        this.roles = roles;
        this.personProvisioning = personProvisioning;
        this.providerOrgMembership = providerOrgMembership;
        this.orgResolver = orgResolver;
        this.events = events;
        this.escalationGuard = escalationGuard;
        this.transactionTemplate = transactionTemplate;
        this.auditLog = auditLog;
        this.entitlements = entitlements;
        this.securityPolicies = securityPolicies;
    }

    /** The id → code map the member listing renders with — see {@link RoleRepository#codeMapByOrgId}. */
    @Transactional(readOnly = true)
    Map<UUID, String> roleCodes(UUID orgId) {
        return roles.codeMapByOrgId(orgId);
    }

    /**
     * One person's role code in ONE organization, read from that organization's own schema.
     *
     * <p><b>This replaces a batch, and the regression is deliberate and recorded</b> (ADR 0010 §5.4).
     * {@code GET /me/organizations} used to collect every role id across every org the caller belongs
     * to and resolve them in a single query — a fix, with its own comment, for a version that paid a
     * query per organization. Post-split those role rows live in different tenant schemas, so one
     * query cannot see them: a batch that still worked would only be working because every tenant
     * happens to share {@code tenant_pool} today, and it would return nothing for the first tenant
     * promoted to a silo. The endpoint goes back to 1 + N, where <b>N is the caller's OWN org count</b>
     * — one for 100% of the seeded population, a handful for the people this endpoint exists for — and
     * not the tenant count, which is the fan-out the whole design forbids.
     *
     * <p>Not {@code @Transactional}: {@code callAs} must wrap the transaction, never sit inside one
     * (ADR 0010 §3.2 — the schema is chosen at connection borrow), and a single read needs no wider
     * boundary than the one Spring Data opens for it.
     *
     * @return the role's code, or {@code null} when the role has been soft-deleted under the
     *     membership — the same answer the batched map gave by simply not containing the id
     */
    String roleCodeIn(UUID orgId, UUID personId) {
        return TenantContext.callAs(orgId, () -> memberships.roleCodeOf(orgId, personId).orElse(null));
    }

    Membership invite(UUID orgId, String email, String givenName, String familyName, String roleCode) {
        // Resolved twice on purpose. Here for the escalation guard, which must run BEFORE anything is
        // provisioned; again inside saveMembership, because the remote calls in doInvite are network
        // round-trips and the role can be deleted while they are in flight.
        Role role = requireRole(orgId, roleCode);
        escalationGuard.requireCallerHolds(orgId, role.getPermissions());
        return doInvite(orgId, email, givenName, familyName, role);
    }

    /**
     * Invite on BEHALF of a person resolved outside a request — the exchange worker has no
     * authenticated caller, so the escalation guard runs against the REQUESTER'S current
     * permissions instead (revocation between submit and processing takes effect).
     */
    Membership inviteAs(UUID requesterPersonId, UUID orgId, String email, String givenName,
            String familyName, String roleCode) {
        Role role = requireRole(orgId, roleCode);
        escalationGuard.requirePersonHolds(orgId, requesterPersonId, role.getPermissions());
        return doInvite(orgId, email, givenName, familyName, role);
    }

    private Membership doInvite(UUID orgId, String email, String givenName, String familyName, Role role) {
        // The plan gate runs BEFORE anything is provisioned — a refused invite must leave no
        // half-created identity behind. Counts live members; re-invites of existing members pass
        // through saveMembership's idempotent return either way.
        entitlements.requireWithinLimit(orgId,
                ug.co.smsone.subscription.EntitlementKeys.MEMBERS_MAX, memberships.countByOrgId(orgId));
        // Resolved here rather than at the point of use, for the same reason the plan gate runs first:
        // an invite that cannot be completed must leave no half-created identity behind.
        String externalOrgId = requireProviderOrgId(orgId);
        // An org that requires MFA enrolls the invitee in TOTP on first login — the policy reaches
        // the invite, not just the session check.
        ug.co.smsone.access.OrgSecurityPolicies policies = securityPolicies.getIfAvailable();
        boolean requireTotp = policies != null && policies.requiresMfa(orgId);
        ProvisionedPerson provisioned = personProvisioning.provision(
                new ProvisionRequest(email, givenName, familyName, requireTotp));
        providerOrgMembership.attach(provisioned.personId(), externalOrgId);
        // Explicit template, not @Transactional: this is a self-invocation, which never reaches the
        // proxy — the same reason remove() opens its transaction this way.
        return transactionTemplate.execute(tx -> saveMembership(orgId, provisioned.personId(), role.getCode()));
    }

    /**
     * Idempotent: a re-invite of an existing member returns the current membership unchanged (role
     * changes go through assignRole, which is last-owner protected). The
     * {@code uq_membership_org_person_live} index backstops a concurrent insert, and losing that race
     * resolves to the winner's row (idempotent success, not a 500).
     *
     * <p>Runs in the caller's transaction, and takes the role by CODE rather than by instance: an
     * instance resolved before the remote calls may be seconds stale, and since soft delete a deleted
     * role still satisfies {@code membership.role_id}, so the insert would succeed against a hidden
     * row. The member would then appear with a null role code and zero permissions, created by a 201,
     * with nothing anywhere explaining why. The shared lock makes the re-read decisive rather than
     * merely narrower — see {@link RoleRepository#lockByOrgIdAndCode}.
     *
     * <p><b>The routing index is written here, in this same transaction, on BOTH paths</b> (ADR 0010
     * §2.1). Same transaction because the alternative — write it after the commit — leaves a window in
     * which a crash produces a member who cannot see their own organization, and nothing anywhere
     * would notice until they said so. Both paths because the idempotent re-invite is the cheapest
     * place to heal a membership older than the index itself: this method is the only one that knows a
     * live membership exists right now, whoever created it.
     *
     * <p><b>The two rows are in different TIERS and still in one transaction, on the tenant's axis.</b>
     * {@code membership} is bare and lands in this tenant's schema; the index row names
     * {@code platform.org_membership_index} outright and lands in the platform's. Nothing here has to
     * change axis, so nothing here has to give up atomicity — which is the whole reason the index is
     * addressed by name (see {@link OrgMembershipIndex}). If it were reached bare instead, this method
     * would need two pins, therefore two connections, therefore two commits, and a crash between them
     * would produce exactly the invisible member the shared transaction rules out.
     */
    Membership saveMembership(UUID orgId, UUID personId, String roleCode) {
        Membership membership = memberships.findByOrgIdAndPersonId(orgId, personId)
                .orElseGet(() -> {
                    Role role = lockRole(orgId, roleCode);
                    try {
                        Membership created = memberships.save(
                                Membership.create(orgId, personId, role.getId(), role.getCode()));
                        auditLog.record("organization.member_added", orgId, personId.toString(), null,
                                "role=" + role.getCode());
                        return created;
                    } catch (DataIntegrityViolationException ex) {
                        return memberships.findByOrgIdAndPersonId(orgId, personId)
                                .orElseThrow(() -> ex);
                    }
                });
        membershipIndex.record(orgId, personId, membership.getStatus());
        return membership;
    }

    Window<Membership> list(UUID orgId, CursorPageRequest page) {
        return memberships.findBy(
                (root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                query -> query.limit(page.size()).sortBy(MEMBER_SORT).scroll(page.scrollPosition(MEMBER_SORT)));
    }

    @Transactional
    Membership assignRole(UUID orgId, UUID personId, String newRoleCode) {
        Membership membership = requireMembership(orgId, personId);
        Role newRole = lockRole(orgId, newRoleCode); // same shared lock as the insert path: no assign-onto-deleted
        if (membership.getRoleId().equals(newRole.getId())) {
            return membership; // no-op, avoids a spurious event + cache flush
        }
        // No OrgMembershipIndex write anywhere in this method, and that is the invariant rather than an
        // omission: the index carries no role, because it routes and never authorizes. A re-role does
        // not change which organizations this person belongs to, so there is nothing for it to learn.
        escalationGuard.requireCallerHolds(orgId, newRole.getPermissions());
        guardLastOwnerLoss(orgId, membership); // demoting the last owner would lock the org out
        String previousRole = roles.findById(membership.getRoleId()).map(Role::getCode).orElse(null);
        membership.assignRole(newRole.getId()); // publishes MembershipRoleChanged
        Membership saved = memberships.save(membership);
        auditLog.record("organization.member_role_changed", orgId, personId.toString(),
                "role=" + previousRole, "role=" + newRole.getCode());
        return saved;
    }


    void remove(UUID orgId, UUID personId) {
        // Local delete commits first (with the last-owner guard's row locks released at commit);
        // the provider unlink runs AFTER, outside the transaction — a remote round-trip must never
        // hold a Hikari connection or the pessimistic owner-row locks.
        transactionTemplate.executeWithoutResult(tx -> {
            Membership membership = requireMembership(orgId, personId);
            guardLastOwnerLoss(orgId, membership);
            String previousRole = roles.findById(membership.getRoleId()).map(Role::getCode).orElse(null);
            memberships.delete(membership);
            // Inside the same transaction as the delete, for the reason saveMembership gives: the seat
            // and the row that advertises it must go together, or the removed member keeps this
            // organization in their switcher until the nightly reconciler notices. Harmless — the
            // authoritative check runs on arrival and denies — but it is a lie the UI would tell.
            membershipIndex.forget(orgId, personId);
            // A delete does not trigger @DomainEvents, so publish explicitly — evicts the permission cache.
            events.publishEvent(new MemberRemoved(orgId, personId, Instant.now()));
            auditLog.record("organization.member_removed", orgId, personId.toString(),
                    "role=" + previousRole, null);
        });
        try {
            // Unlink from the provider organization; the person's account is kept.
            orgResolver.keycloakOrgIdOf(orgId)
                    .ifPresent(externalOrgId -> providerOrgMembership.detach(personId, externalOrgId));
        } catch (RuntimeException ex) {
            // Access is already revoked (membership row gone → zero permissions, fail closed). The
            // lingering provider link only affects the token's organization claim; surface it for
            // ops instead of failing a removal that already happened.
            log.error("Person {} removed from org {} locally, but the provider unlink failed: {}",
                    personId, orgId, ex.toString());
        }
    }

    /**
     * The provider organization this tenant's tokens are scoped by. Absent is not a tolerable no-op on
     * the invite path: the {@code organization} claim is what the edge resolves the tenant from, so a
     * member attached to nothing would be created by a 201 and then hold no access anywhere, with
     * nothing naming why. Every org created here is linked, so an absent link is a broken invariant and
     * says so.
     */
    private String requireProviderOrgId(UUID orgId) {
        return orgResolver.keycloakOrgIdOf(orgId).orElseThrow(() -> new IllegalStateException(
                "Organization " + orgId + " has no provider organization link; members cannot be attached."));
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

    private Membership requireMembership(UUID orgId, UUID personId) {
        return memberships.findByOrgIdAndPersonId(orgId, personId)
                .orElseThrow(() -> new NotFoundException("Member not found in this organization."));
    }
}
