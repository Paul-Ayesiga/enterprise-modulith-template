package ug.co.smsone.organization.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The tenant is addressed by its own id now, so {@code findByKcOrgId} / {@code findByKcOrgIdIn} are
 * gone — {@code findById} / {@code findAllById} are the same lookups against the primary key.
 */
interface OrganizationRepository extends JpaRepository<Organization, UUID>, JpaSpecificationExecutor<Organization> {

    Optional<Organization> findByAlias(String alias);

    boolean existsByAlias(String alias);
}
