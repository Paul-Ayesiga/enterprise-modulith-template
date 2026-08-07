package ug.co.smsone.organization.internal;

import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Organizations;
import ug.co.smsone.organization.ProvisionedOrganization;

/** {@link Organizations} over the internal service — signup provisions exactly like the admin path. */
@Component
class OrganizationsImpl implements Organizations {

    private final OrganizationService service;

    OrganizationsImpl(OrganizationService service) {
        this.service = service;
    }

    @Override
    public ProvisionedOrganization create(String alias, String name, String ownerEmail, String ownerGivenName,
            String ownerFamilyName) {
        OrganizationService.NewOrganization created =
                service.create(alias, name, ownerEmail, ownerGivenName, ownerFamilyName);
        return new ProvisionedOrganization(created.organization().getId(), created.ownerPersonId());
    }
}
