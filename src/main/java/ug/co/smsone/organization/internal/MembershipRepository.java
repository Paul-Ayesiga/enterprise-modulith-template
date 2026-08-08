package ug.co.smsone.organization.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MembershipRepository extends JpaRepository<Membership, UUID>, JpaSpecificationExecutor<Membership> {

    Optional<Membership> findByOrgIdAndPersonId(UUID orgId, UUID personId);

    long countByOrgId(UUID orgId);

    /**
     * One member's role code, resolved inside ONE organization's schema.
     *
     * <p>An ad-hoc join rather than a mapped association: {@code membership.role_id} is a plain column
     * with a real FK (V11, intra-module and intra-tenant, so both of AGENTS §1's FK clauses are
     * satisfied), and nothing else in this module needs the object graph. {@code Role} carries
     * {@code @SQLRestriction("deleted_at is null")}, so a membership whose role was soft-deleted under
     * it yields EMPTY rather than a code — the same answer the batched id→code map gave by simply not
     * containing the id, and normal, because a membership outlives its role (the FK is blind to
     * {@code deleted_at}).
     *
     * <p>There is deliberately no person-first query left in this repository. {@code membership} is
     * tenant-tier (ADR 0010 §2), so "every org this person belongs to" cannot be asked of one schema
     * once tenants are siloed — it is answered by {@code platform.org_membership_index}, and leaving a
     * {@code findByPersonId…} here would be an invitation to re-introduce the fan-out the index exists
     * to prevent.
     */
    @Query("select r.code from Membership m, Role r "
            + "where m.orgId = :orgId and m.personId = :personId and r.id = m.roleId")
    Optional<String> roleCodeOf(@Param("orgId") UUID orgId, @Param("personId") UUID personId);

    /**
     * Row-locks the active members holding a given role for the duration of the transaction. Two
     * concurrent owner removals/demotions contend on the same rows and serialize, closing the
     * last-owner TOCTOU race (both reading count==2 and both committing to zero owners).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.orgId = :orgId and m.roleId = :roleId and m.status = :status")
    List<Membership> lockByOrgIdAndRoleIdAndStatus(@Param("orgId") UUID orgId, @Param("roleId") UUID roleId,
            @Param("status") MembershipStatus status);

    /** Whether any membership references a role — blocks deleting a role that is still assigned. */
    boolean existsByRoleId(UUID roleId);

    List<Membership> findByOrgIdAndRoleId(UUID orgId, UUID roleId);
}
