package ug.co.smsone.integration.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface IntegrationRepository extends JpaRepository<Integration, UUID> {

    Optional<Integration> findByOrgIdAndKind(UUID orgId, String kind);

    Optional<Integration> findByOrgIdIsNullAndKind(String kind);

    Optional<Integration> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<Integration> findByIdAndOrgIdIsNull(UUID id);

    List<Integration> findByOrgIdOrderByKindAsc(UUID orgId);

    List<Integration> findByOrgIdIsNullOrderByKindAsc();
}
