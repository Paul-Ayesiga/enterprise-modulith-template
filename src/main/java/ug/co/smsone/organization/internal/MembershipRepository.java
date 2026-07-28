package ug.co.smsone.organization.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface MembershipRepository extends JpaRepository<Membership, UUID>, JpaSpecificationExecutor<Membership> {

    Optional<Membership> findByOrgIdAndUserSubject(UUID orgId, String userSubject);

    /** Active members holding a given role — drives last-owner protection. */
    long countByOrgIdAndRoleIdAndStatus(UUID orgId, UUID roleId, MembershipStatus status);

    /** Whether any membership references a role — blocks deleting a role that is still assigned. */
    boolean existsByRoleId(UUID roleId);
}
