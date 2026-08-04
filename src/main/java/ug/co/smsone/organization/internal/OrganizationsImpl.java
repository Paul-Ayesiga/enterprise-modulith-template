package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Organizations;

/** {@link Organizations} over the internal service — signup provisions exactly like the admin path. */
@Component
class OrganizationsImpl implements Organizations {

    private final OrganizationService service;

    OrganizationsImpl(OrganizationService service) {
        this.service = service;
    }

    @Override
    public UUID create(String alias, String name, String ownerEmail, String ownerFirstName, String ownerLastName) {
        return service.create(alias, name, ownerEmail, ownerFirstName, ownerLastName).getKcOrgId();
    }
}
