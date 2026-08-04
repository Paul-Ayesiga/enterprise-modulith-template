package ug.co.smsone.settings.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface FeatureFlagOrgOverrideRepository extends JpaRepository<FeatureFlagOrgOverride, UUID> {

    Optional<FeatureFlagOrgOverride> findByFlagKeyAndOrgId(String flagKey, UUID orgId);
}
