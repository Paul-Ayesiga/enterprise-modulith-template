package ug.co.smsone.geo.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface GeoCapturePolicyRepository extends JpaRepository<GeoCapturePolicy, UUID> {

    Optional<GeoCapturePolicy> findByOrgIdAndSubjectType(UUID orgId, String subjectType);
}
