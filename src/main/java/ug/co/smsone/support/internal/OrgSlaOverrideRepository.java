package ug.co.smsone.support.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrgSlaOverrideRepository extends JpaRepository<OrgSlaOverride, UUID> {

    Optional<OrgSlaOverride> findByOrgIdAndPriority(UUID orgId, String priority);

    List<OrgSlaOverride> findByOrgIdOrderByPriorityAsc(UUID orgId);

    void deleteByOrgIdAndPriority(UUID orgId, String priority);
}
