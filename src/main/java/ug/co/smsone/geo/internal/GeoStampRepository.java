package ug.co.smsone.geo.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface GeoStampRepository extends JpaRepository<GeoStampEntity, UUID> {

    /** A subject's stamps, newest first (a record may be re-stamped over its life). */
    List<GeoStampEntity> findByOrgIdAndSubjectTypeAndSubjectIdOrderByCapturedAtDesc(
            UUID orgId, String subjectType, String subjectId);
}
