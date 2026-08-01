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

    Optional<Membership> findByOrgIdAndUserSubject(UUID orgId, String userSubject);

    long countByOrgId(UUID orgId);

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
}
