package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.OrgDirectory;

/** The {@link OrgDirectory} port: the same service the REST controller calls, entity mapped out. */
@Component
class OrgDirectoryImpl implements OrgDirectory {

    private final OrganizationService organizations;

    OrgDirectoryImpl(OrganizationService organizations) {
        this.organizations = organizations;
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

    static OrgSummary toSummary(Organization organization) {
        return new OrgSummary(organization.getId(), organization.getAlias(), organization.getName(),
                organization.getStatus().name(), organization.getCreatedAt());
    }
}
