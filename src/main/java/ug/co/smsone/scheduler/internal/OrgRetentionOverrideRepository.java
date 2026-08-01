package ug.co.smsone.scheduler.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrgRetentionOverrideRepository extends JpaRepository<OrgRetentionOverride, UUID> {

    List<OrgRetentionOverride> findByScope(String scope);

    List<OrgRetentionOverride> findByOrgIdOrderByScopeAsc(UUID orgId);

    Optional<OrgRetentionOverride> findByOrgIdAndScope(UUID orgId, String scope);

    void deleteByOrgIdAndScope(UUID orgId, String scope);
}
