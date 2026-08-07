package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ug.co.smsone.organization.ProvisionedOrganization;

/** The port is pure delegation onto the audited admin path — prove the wiring, nothing else. */
class OrganizationsImplTest {

    @Test
    void delegatesToTheStandardCreatePath() {
        OrganizationService service = mock(OrganizationService.class);
        Organization organization = mock(Organization.class);
        UUID orgId = UUID.randomUUID();
        UUID ownerPersonId = UUID.randomUUID();
        given(organization.getId()).willReturn(orgId);
        given(service.create("acme", "Acme", "a@b.test", "Ada", null))
                .willReturn(new OrganizationService.NewOrganization(organization, ownerPersonId));

        // Both ids come out, and both are this platform's own: the port used to hand back the Keycloak
        // organization id, and signup could not name the person the call had just provisioned at all.
        assertThat(new OrganizationsImpl(service).create("acme", "Acme", "a@b.test", "Ada", null))
                .isEqualTo(new ProvisionedOrganization(orgId, ownerPersonId));
    }
}
