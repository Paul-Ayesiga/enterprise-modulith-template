package ug.co.smsone.organization.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface RoleRepository extends JpaRepository<Role, UUID>, JpaSpecificationExecutor<Role> {

    Optional<Role> findByOrgIdAndCode(UUID orgId, String code);

    List<Role> findByOrgId(UUID orgId);

    boolean existsByOrgId(UUID orgId);
}
