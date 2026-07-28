package ug.co.smsone.organization.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface MembershipRepository extends JpaRepository<Membership, UUID>, JpaSpecificationExecutor<Membership> {

    Optional<Membership> findByOrgIdAndUserSubject(UUID orgId, String userSubject);
}
