package ug.co.smsone.organization.internal;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.OrgDirectory;

/** The {@link OrgDirectory} port: the same service the REST controller calls, entity mapped out. */
@Component
class OrgDirectoryImpl implements OrgDirectory {

    private final OrganizationService organizations;
    private final OrganizationRepository repository;

    OrgDirectoryImpl(OrganizationService organizations, OrganizationRepository repository) {
        this.organizations = organizations;
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public OrgSummary get(UUID orgId) {
        return toSummary(organizations.require(orgId));
    }

    @Override
    public OrgSummary rename(UUID orgId, String name) {
        return toSummary(organizations.rename(orgId, name));
    }

    /**
     * One {@code in (…)} against the primary key. Soft-deleted organizations are excluded by the
     * entity's own {@code @SQLRestriction}, which is the answer we want: usage reported for a tenant
     * that has been deleted is not billable.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> existing(Collection<UUID> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return Set.of();
        }
        return repository.findAllById(orgIds).stream()
                .map(Organization::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    static OrgSummary toSummary(Organization organization) {
        return new OrgSummary(organization.getId(), organization.getAlias(), organization.getName(),
                organization.getStatus().name(), organization.getCreatedAt());
    }
}
