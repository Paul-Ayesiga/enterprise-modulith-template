package ug.co.smsone.access.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrgSecurityPolicyRepository extends JpaRepository<OrgSecurityPolicy, UUID> {

    Optional<OrgSecurityPolicy> findByOrgId(UUID orgId);
}
