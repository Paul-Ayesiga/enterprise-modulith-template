package ug.co.smsone.settings.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID>, JpaSpecificationExecutor<FeatureFlag> {

    Optional<FeatureFlag> findByKey(String key);
}
