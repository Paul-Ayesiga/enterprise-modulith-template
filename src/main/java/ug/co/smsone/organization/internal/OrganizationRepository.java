package ug.co.smsone.organization.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface OrganizationRepository extends JpaRepository<Organization, UUID>, JpaSpecificationExecutor<Organization> {

    Optional<Organization> findByKcOrgId(UUID kcOrgId);

    boolean existsByKcOrgId(UUID kcOrgId);
}
